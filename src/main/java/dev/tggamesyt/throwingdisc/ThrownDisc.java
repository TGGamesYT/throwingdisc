package dev.tggamesyt.throwingdisc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.MoveSimulationType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A thrown disc. It flies straight (no gravity) and cuts through every entity in its way, damaging and knocking each
 * back once. It ricochets off hard surfaces (anything not in the {@link ThrowingDisc#SOFT_SURFACES} tag); every
 * ricochet makes it faster and lets it fly further. Soft surfaces, shields and running out of distance turn it back
 * to its thrower: it then flies through blocks straight at them, hitting things on the way again, and goes back into
 * the slot it was thrown from. Blocks it hits react like to any projectile (it is an impact projectile, so decorated pots
 * break like with arrows). Every ricochet costs a point of durability (it can break
 * in the air). Its rotation follows where it flies, so it is tilted like it was thrown and flips over when it bounces.
 * <p>
 * Everything is worked out on the server; clients only draw it where the server says it is (smoothly following it).
 * A disc in flight keeps the chunks around it loaded and turns back before flying into unloaded ones. It only hits
 * living things, and not the thrower's teammates. If the thrower leaves (or the server stops) while it flies, it goes
 * straight back into their inventory.
 */
public class ThrownDisc extends Projectile {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(ThrownDisc.class, EntityDataSerializers.ITEM_STACK);

    /** Blocks per tick when thrown. */
    private static final double SPEED = 1.4;
    private static final double MAX_SPEED = 2.0;
    /** Each ricochet makes it this much faster... */
    private static final double RICOCHET_SPEEDUP = 1.05;
    /** ...and lets it fly this many blocks further before it turns back. */
    private static final double RICOCHET_DISTANCE = 12.0;
    /** How close to the thrower it has to get to be caught. */
    private static final double CATCH_DISTANCE = 0.6;
    private static final int MAX_RICOCHETS = 8;
    /** How far it flies out before turning back. */
    private static final double RANGE = 16.0;
    /** It drops if it has not made it back by then (60 seconds). */
    private static final int MAX_AGE = 1200;
    private static final double KNOCKBACK = 0.6;
    /** A fast disc can ricochet a few times in one tick; this many bounces are worked out per tick at most. */
    private static final int STEPS_PER_TICK = 4;
    /** Discs in flight on the server, to give back to throwers who leave. */
    private static final Set<ThrownDisc> IN_FLIGHT = new HashSet<>();

    private ItemStack disc = ItemStack.EMPTY;
    private float damage = 7.0F;
    private double speed = SPEED;
    private double distanceLeft = RANGE;
    private int ricochets;
    private boolean returning;
    /** The inventory slot it was thrown from, to go back into. */
    private int slot = -1;
    /** Thrown in creative: the player still has it, so this one just goes away when it is back. */
    private boolean creativeCopy;
    private int age;
    /** What it hit on this way (out or back), so each is hit once per way. */
    private final Set<Integer> hitThisWay = new HashSet<>();

    public ThrownDisc(EntityType<? extends ThrownDisc> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    static void launch(ServerLevel level, Player thrower, ItemStack stack, int slot, float damage, boolean creative) {
        ThrownDisc disc = new ThrownDisc(ThrowingDisc.THROWN_DISC, level);
        disc.setOwner(thrower);
        disc.setPos(thrower.getX(), thrower.getEyeY() - 0.1, thrower.getZ());
        disc.disc = stack;
        disc.getEntityData().set(DATA_ITEM, stack.copy());
        disc.slot = slot;
        disc.damage = damage;
        disc.creativeCopy = creative;
        disc.setDeltaMovement(thrower.getLookAngle().scale(SPEED));
        disc.faceMotion();
        level.addFreshEntity(disc);
    }

    /** The disc, for drawing it. */
    public ItemStack discStack() {
        return getEntityData().get(DATA_ITEM);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    /** Gives every disc the player has in flight back to them (they are leaving). */
    public static void recoverFor(ServerPlayer player) {
        for (ThrownDisc disc : List.copyOf(IN_FLIGHT)) {
            if (!disc.isRemoved() && disc.getOwner() == player && disc.level() instanceof ServerLevel level) {
                disc.giveBack(level, player);
            }
        }
    }

    static void forgetAll() {
        IN_FLIGHT.clear();
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        IN_FLIGHT.remove(this);
        super.onRemoval(reason);
    }

    /** Clients follow the server's position smoothly instead of guessing the flight themselves. */
    @Override
    public MoveSimulationType getMoveSimulationType() {
        return MoveSimulationType.AUTHORITATIVE_SIDE;
    }

    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return LinearInterpolationHandler.create(this, 2);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        IN_FLIGHT.add(this);
        if (++age > MAX_AGE) {
            drop(level);
            return;
        }
        if (returning) {
            flyBack(level);
        } else {
            flyOut(level);
        }
        if (!isRemoved()) {
            level.getChunkSource().addTicketWithRadius(ThrowingDisc.FLIGHT_TICKET, chunkPosition(), 2);
        }
        faceMotion();
    }

    private static boolean loaded(ServerLevel level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Turns the disc to lie along its flight: flat when flying level, tilted when flying up or down. */
    private void faceMotion() {
        Vec3 motion = getDeltaMovement();
        if (motion.lengthSqr() < 1.0E-7) {
            return;
        }
        setYRot((float) Math.toDegrees(Math.atan2(-motion.x, motion.z)));
        setXRot((float) -Math.toDegrees(Math.atan2(motion.y, motion.horizontalDistance())));
    }

    private void flyOut(ServerLevel level) {
        // Fly this tick's distance; after a ricochet, the rest of it goes the new way.
        double timeLeft = 1.0;
        for (int step = 0; step < STEPS_PER_TICK && timeLeft > 1.0E-5 && !returning && !isRemoved(); step++) {
            Vec3 from = position();
            Vec3 motion = getDeltaMovement().scale(timeLeft);
            if (!loaded(level, from.add(motion))) {
                turnBack();
                return;
            }
            BlockHitResult block = level.clip(new ClipContext(from, from.add(motion), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            Vec3 to = block.getType() == HitResult.Type.MISS ? from.add(motion) : block.getLocation();
            hitEntities(level, from, to);
            if (isRemoved() || returning) {
                return;
            }
            double travelled = from.distanceTo(to);
            distanceLeft -= travelled;
            if (block.getType() == HitResult.Type.MISS) {
                setPos(to);
                if (distanceLeft <= 0) {
                    turnBack();
                }
                return;
            }
            timeLeft *= 1.0 - Math.min(1.0, travelled / Math.max(motion.length(), 1.0E-9));
            BlockPos pos = block.getBlockPos();
            // The block reacts like to any projectile (pots break, targets light up, ...); the disc still bounces off
            // it, also when that broke it.
            BlockState state = level.getBlockState(pos);
            state.onProjectileHit(level, state, block, this);
            Vec3 normal = Vec3.atLowerCornerOf(block.getDirection().getUnitVec3i());
            setPos(to.add(normal.scale(0.05)));
            if (state.is(ThrowingDisc.SOFT_SURFACES) || ricochets >= MAX_RICOCHETS) {
                playSound(SoundEvents.TRIDENT_HIT_GROUND, 1.0F, 1.0F);
                turnBack();
                return;
            }
            // Ricochet: bounce off the surface, faster and with more distance to go. It wears the disc.
            if (wear(level)) {
                return;
            }
            ricochets++;
            speed = Math.min(MAX_SPEED, speed * RICOCHET_SPEEDUP);
            distanceLeft += RICOCHET_DISTANCE;
            Vec3 velocity = getDeltaMovement();
            Vec3 bounced = velocity.subtract(normal.scale(2 * velocity.dot(normal)));
            setDeltaMovement(bounced.normalize().scale(speed));
            needsSync = true;
            hitThisWay.clear();
            playSound(SoundEvents.TRIDENT_HIT_GROUND, 1.0F, 1.4F);
        }
    }

    private void flyBack(ServerLevel level) {
        Entity owner = getOwner();
        if (owner == null || !owner.isAlive() || owner.level() != level || owner.isSpectator()) {
            drop(level);
            return;
        }
        Vec3 from = position();
        Vec3 target = owner.getEyePosition().subtract(0, 0.4, 0);
        Vec3 toOwner = target.subtract(from);
        double distance = toOwner.length();
        // Caught only once it is really at the thrower: the last step lands it on them, and they catch it the
        // tick after, so it is seen flying all the way in (a fast disc used to vanish a few blocks away).
        if (distance < CATCH_DISTANCE) {
            giveBack(level, owner);
            return;
        }
        Vec3 motion = toOwner.scale(Math.min(speed, distance) / distance);
        if (!loaded(level, from.add(motion))) {
            giveBack(level, owner);
            return;
        }
        hitEntities(level, from, from.add(motion));
        setDeltaMovement(motion);
        setPos(from.add(motion));
    }

    /** One point of durability for a ricochet. True if that broke it (it is gone then). */
    private boolean wear(ServerLevel level) {
        if (creativeCopy) {
            return false;
        }
        Entity owner = getOwner();
        boolean[] broke = {false};
        disc.hurtAndBreak(1, level, owner instanceof ServerPlayer player ? player : null, item -> broke[0] = true);
        if (broke[0] || disc.isEmpty()) {
            playSound(SoundEvents.ITEM_BREAK.value(), 1.0F, 1.0F);
            discard();
            return true;
        }
        getEntityData().set(DATA_ITEM, disc.copy());
        return false;
    }

    private void turnBack() {
        returning = true;
        hitThisWay.clear();
        playSound(SoundEvents.TRIDENT_RETURN, 1.0F, 1.2F);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hitting things
    // ---------------------------------------------------------------------------------------------------------

    /** Living things other than the thrower and their teammates, each once per way. */
    @Override
    protected boolean canHitEntity(Entity entity) {
        Entity owner = getOwner();
        return entity instanceof LivingEntity && entity != owner && super.canHitEntity(entity)
                && !hitThisWay.contains(entity.getId()) && (owner == null || owner.doTeamsAllowDamage(entity));
    }

    /** Hits everything along the way from one point to the other, nearest first. */
    private void hitEntities(ServerLevel level, Vec3 from, Vec3 to) {
        AABB area = new AABB(from, to).inflate(1.0);
        for (Entity entity : level.getEntities(this, area, this::canHitEntity).stream()
                .sorted((a, b) -> Double.compare(a.distanceToSqr(from), b.distanceToSqr(from))).toList()) {
            if (entity.getBoundingBox().inflate(0.3).clip(from, to).isPresent()) {
                hit(level, entity, to.subtract(from));
                if (returning && !hitThisWay.isEmpty() && isRemoved()) {
                    return;
                }
            }
        }
    }

    private void hit(ServerLevel level, Entity target, Vec3 direction) {
        hitThisWay.add(target.getId());
        Entity owner = getOwner();
        DamageSource source = damageSources().trident(this, owner == null ? this : owner);
        float amount = EnchantmentHelper.modifyDamage(level, disc, target, source, damage);
        boolean hurt = target.hurtServer(level, source, amount);
        if (hurt) {
            if (target instanceof LivingEntity living && direction.horizontalDistanceSqr() > 1.0E-7) {
                Vec3 push = direction.normalize();
                living.knockback(KNOCKBACK, -push.x, -push.z, source, amount);
            }
            EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, source, disc);
            playSound(SoundEvents.TRIDENT_HIT, 1.0F, 1.0F);
        } else if (!returning && target instanceof LivingEntity living && living.isBlocking()) {
            // Bounces off a raised shield and comes back.
            playSound(SoundEvents.SHIELD_BLOCK.value(), 1.0F, 1.0F);
            turnBack();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Coming back
    // ---------------------------------------------------------------------------------------------------------

    /** Back with the thrower: into the slot it was thrown from, or anywhere in the inventory, or on the ground. */
    void giveBack(ServerLevel level, Entity owner) {
        if (!creativeCopy) {
            if (owner instanceof Player player) {
                Inventory inventory = player.getInventory();
                if (slot >= 0 && slot < inventory.getContainerSize() && inventory.getItem(slot).isEmpty()) {
                    inventory.setItem(slot, disc);
                } else if (!inventory.add(disc)) {
                    spawnAtLocation(level, disc);
                }
                player.take(this, 1);
            } else {
                spawnAtLocation(level, disc);
            }
        }
        playSound(SoundEvents.ITEM_PICKUP, 0.4F, 1.4F);
        discard();
    }

    private void drop(ServerLevel level) {
        if (!creativeCopy && !disc.isEmpty()) {
            spawnAtLocation(level, disc);
        }
        discard();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (!disc.isEmpty()) {
            output.store("Disc", ItemStack.CODEC, disc);
        }
        output.putFloat("Damage", damage);
        output.putDouble("Speed", speed);
        output.putDouble("DistanceLeft", distanceLeft);
        output.putInt("Ricochets", ricochets);
        output.putBoolean("Returning", returning);
        output.putInt("Slot", slot);
        output.putBoolean("CreativeCopy", creativeCopy);
        output.putInt("Age", age);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        disc = input.read("Disc", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        getEntityData().set(DATA_ITEM, disc.copy());
        damage = input.getFloatOr("Damage", 7.0F);
        speed = input.getDoubleOr("Speed", SPEED);
        distanceLeft = input.getDoubleOr("DistanceLeft", RANGE);
        ricochets = input.getIntOr("Ricochets", 0);
        returning = input.getBooleanOr("Returning", false);
        slot = input.getIntOr("Slot", -1);
        creativeCopy = input.getBooleanOr("CreativeCopy", false);
        age = input.getIntOr("Age", 0);
    }
}
