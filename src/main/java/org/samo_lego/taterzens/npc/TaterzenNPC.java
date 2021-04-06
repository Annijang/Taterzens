package org.samo_lego.taterzens.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.samo_lego.taterzens.Taterzens;
import org.samo_lego.taterzens.compatibility.DisguiseLibCompatibility;
import org.samo_lego.taterzens.interfaces.TaterzenEditor;
import org.samo_lego.taterzens.interfaces.TaterzenPlayer;
import org.samo_lego.taterzens.mixin.accessors.EntityTrackerEntryAccessor;
import org.samo_lego.taterzens.mixin.accessors.PlayerListS2CPacketAccessor;
import org.samo_lego.taterzens.mixin.accessors.ThreadedAnvilChunkStorageAccessor;
import org.samo_lego.taterzens.npc.ai.goal.DirectPathGoal;
import org.samo_lego.taterzens.npc.ai.goal.ReachMeleeAttackGoal;
import org.samo_lego.taterzens.util.TextUtil;

import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.ADD_PLAYER;
import static net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.REMOVE_PLAYER;
import static org.samo_lego.taterzens.Taterzens.*;
import static org.samo_lego.taterzens.mixin.accessors.PlayerEntityAccessor.getPLAYER_MODEL_PARTS;

/**
 * The NPC itself.
 */
public class TaterzenNPC extends HostileEntity implements CrossbowUser, RangedAttackMob {

    /**
     * Data of the NPC.
     */
    private final NPCData npcData = new NPCData();

    private final PlayerManager playerManager;
    private final MinecraftServer server;
    private PlayerEntity fakePlayer;
    private GameProfile gameProfile;

    // Goals
    private final LookAtEntityGoal lookPlayerGoal = new LookAtEntityGoal(this, PlayerEntity.class, 8.0F);
    private final LookAroundGoal lookAroundGoal = new LookAroundGoal(this);

    private final FollowTargetGoal<PlayerEntity> followTargetGoal = new FollowTargetGoal<>(this, PlayerEntity.class, false, true);
    private final WanderAroundGoal wanderAroundFarGoal = new WanderAroundGoal(this, 0.4F, 30);
    private final GoToWalkTargetGoal pathGoal = new GoToWalkTargetGoal(this, 0.4F);
    private final DirectPathGoal directPathGoal = new DirectPathGoal(this, 0.4F);

    private final CrossbowAttackGoal<TaterzenNPC> crossbowAttackGoal = new CrossbowAttackGoal<>(this, 1.0D, 40.0F);
    private final BowAttackGoal<TaterzenNPC> bowAttackGoal = new BowAttackGoal<>(this, 1.0D, 20, 40.0F);
    private final ReachMeleeAttackGoal reachMeleeAttackGoal = new ReachMeleeAttackGoal(this, 1.2D, false);
    private short ticks = 0;

    /**
     * Creates a TaterzenNPC.
     * You'd probably want to use
     * {@link TaterzenNPC#TaterzenNPC(ServerWorld, String, Vec3d, float[])} or {@link TaterzenNPC#TaterzenNPC(ServerPlayerEntity, String)} or
     * {@link TaterzenNPC#TaterzenNPC(ServerPlayerEntity, String)} instead, as this one doesn't set the position and custom name.
     *
     * @param entityType
     * @param world
     */
    public TaterzenNPC(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
        this.stepHeight = 0.6F;
        this.setCanPickUpLoot(false);
        this.setCustomNameVisible(true);
        this.setCustomName(this.getName());
        this.setInvulnerable(true);
        this.setPersistent();
        this.experiencePoints = 0;
        this.setMovementSpeed(0.4F);
        ((MobNavigation) this.getNavigation()).setCanPathThroughDoors(true);

        this.gameProfile = new GameProfile(this.getUuid(), this.getName().asString());
        if(DISGUISELIB_LOADED) {
            DisguiseLibCompatibility.setGameProfile(this, this.gameProfile);
        }
        this.server = world.getServer();
        this.playerManager = server.getPlayerManager();

        this.fakePlayer = new PlayerEntity(world, this.getBlockPos(), this.headYaw, new GameProfile(this.uuid, null)) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
        this.fakePlayer.getDataTracker().set(getPLAYER_MODEL_PARTS(), (byte) 0x7f);

        TATERZEN_NPCS.add(this);
    }

    public TaterzenNPC(ServerWorld world, String displayName, Vec3d pos, float[] rotations) {
        this(Taterzens.TATERZEN, world);

        this.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), rotations[1], rotations[2]);
        this.setHeadYaw(rotations[0]);
        this.setCustomName(new LiteralText(displayName));
        this.applySkin(SkullBlockEntity.loadProperties(this.gameProfile));
    }

    public TaterzenNPC(ServerPlayerEntity owner, String displayName) {
        this(owner.getServerWorld(), displayName, owner.getPos(), new float[]{owner.headYaw, owner.yaw, owner.pitch});
    }

    /**
     * Sets the *ONLY* command to be executed on right - click
     * @param command command to execute
     * @deprecated please use {@link TaterzenNPC#addCommand(String)}
     */
    @Deprecated
    public void setCommand(String command) {
        this.clearCommands();
        this.npcData.commands.add(command);
    }

    /**
     * Adds command to the list
     * of commands that will be executed on
     * right-clicking the Taterzen.
     * @param command command to add
     */
    public void addCommand(String command) {
        this.npcData.commands.add(command);
    }

    /**
     * Gets all available commands
     * @return array list of commands that will be executed on right click
     */
    public ArrayList<String> getCommands() {
        return this.npcData.commands;
    }

    /**
     * Removes certain command from command list.
     * @param index index of where to remove command
     */
    public void removeCommand(int index) {
        if(index >= 0 && index < this.npcData.commands.size())
            this.npcData.commands.remove(index);
    }

    /**
     * Clears all the commands Taterzen
     * executes on right-click
     */
    public void clearCommands() {
        this.npcData.commands = new ArrayList<>();
    }

    @Override
    protected int getPermissionLevel() {
        return this.npcData.permissionLevel;
    }

    public void setPermissionLevel(int newPermissionLevel) {
        this.npcData.permissionLevel = newPermissionLevel;
    }

    /**
     * Sets {@link org.samo_lego.taterzens.npc.NPCData.Movement movement type}
     * and initialises the goals.
     *
     * @param movement movement type
     */
    public void setMovement(NPCData.Movement movement) {
        this.npcData.movement = movement;
        this.goalSelector.remove(this.wanderAroundFarGoal);
        this.goalSelector.remove(this.directPathGoal);
        this.goalSelector.remove(this.pathGoal);
        this.goalSelector.remove(this.lookPlayerGoal);
        this.goalSelector.remove(this.lookAroundGoal);

        if(movement != NPCData.Movement.NONE && movement != NPCData.Movement.FORCED_LOOK) {
            if(movement == NPCData.Movement.FORCED_PATH) {
                this.goalSelector.add(3, directPathGoal);
            } else {
                this.goalSelector.add(5, lookPlayerGoal);
                this.goalSelector.add(6, lookAroundGoal);
                if(movement == NPCData.Movement.PATH)
                    this.goalSelector.add(3, pathGoal);
                else if(movement == NPCData.Movement.FREE)
                    this.goalSelector.add(3, wanderAroundFarGoal);
            }
        }
    }

    /**
     * Adds block position as a node in path of Taterzen.
     * @param blockPos position to add.
     */
    public void addPathTarget(BlockPos blockPos) {
        this.npcData.pathTargets.add(blockPos);
        this.setPositionTarget(this.npcData.pathTargets.get(0), 1);
    }

    /**
     * Removes node from path targets.
     * @param blockPos position from path to remove
     */
    public void removePathTarget(BlockPos blockPos) {
        this.npcData.pathTargets.remove(blockPos);
    }

    /**
     * Gets the path nodes / targets.
     * @return array list of block positions.
     */
    public ArrayList<BlockPos> getPathTargets() {
        return this.npcData.pathTargets;
    }

    /**
     * Clears all the path nodes / targets.
     */
    public void clearPathTargets() {
        this.npcData.pathTargets = new ArrayList<>();
        this.npcData.currentMoveTarget = 0;
    }

    /**
     * Ticks the movement depending on {@link org.samo_lego.taterzens.npc.NPCData.Movement} type
     */
    @Override
    public void tickMovement() {
        if(++this.ticks >= 20)
            this.ticks = 0;
        if(this.npcData.equipmentEditor != null)
            return;
        if(this.npcData.movement == NPCData.Movement.FORCED_LOOK) {
            Box box = this.getBoundingBox().expand(4.0D);
            this.world.getEntityCollisions(this, box, entity -> {
                if(entity instanceof ServerPlayerEntity) {
                    this.lookAtEntity(entity, 60.0F, 60.0F);
                    this.setHeadYaw(this.yaw);
                    return true;
                }
                return false;
            });
        } else if(this.npcData.movement != NPCData.Movement.NONE) {
            this.yaw = this.headYaw; // Rotates body as well
            if((this.npcData.movement == NPCData.Movement.FORCED_PATH && !this.npcData.pathTargets.isEmpty())) {
                if(this.getPositionTarget().getSquaredDistance(this.getPos(), false) < 5.0D) {
                    if(++this.npcData.currentMoveTarget >= this.npcData.pathTargets.size())
                        this.npcData.currentMoveTarget = 0;
                    // New target
                    this.setPositionTarget(this.npcData.pathTargets.get(this.npcData.currentMoveTarget), 2);
                }
            } else if(this.npcData.movement == NPCData.Movement.PATH && !this.pathGoal.shouldContinue() && !this.npcData.pathTargets.isEmpty()) {
                if(this.npcData.pathTargets.get(this.npcData.currentMoveTarget).getSquaredDistance(this.getPos(), false) < 5.0D) {
                    if(++this.npcData.currentMoveTarget >= this.npcData.pathTargets.size())
                        this.npcData.currentMoveTarget = 0;
                    // New target
                    this.setPositionTarget(this.npcData.pathTargets.get(this.npcData.currentMoveTarget), 1);
                }
            }
            super.tickMovement();
        }
    }

    /**
     * Ticks the Taterzen and sends appropriate messages
     * to players in radius of 2 blocks.
     */
    @Override
    public void tick() {
        super.tick();
        if(!this.npcData.messages.isEmpty()) {
            Box box = this.getBoundingBox().expand(2.0D, 1.0D, 2.0D);
            this.world.getEntityCollisions(this, box, entity -> {
                if(entity instanceof ServerPlayerEntity && !((TaterzenEditor) entity).inMsgEditMode()) {
                    TaterzenPlayer pl = (TaterzenPlayer) entity;
                    int msgPos = pl.getLastMsgPos();
                    if(this.npcData.messages.get(msgPos).getSecond() < pl.ticksSinceLastMessage()) {
                        entity.sendSystemMessage(
                                this.getName().copy().append(" -> you: ").append(this.npcData.messages.get(pl.getLastMsgPos()).getFirst()),
                                this.uuid
                        );
                        // Resetting message counter
                        pl.resetMessageTicks();

                        if(++msgPos >= this.npcData.messages.size())
                            msgPos = 0;
                        // Setting new message position
                        pl.setLastMsgPos(msgPos);
                    }
                    return true;
                }
                return false;
            });
        }
    }
    /**
     * Gets equipment as list of {@link Pair Pairs}.
     * @return equipment list of pairs.
     */
    private List<Pair<EquipmentSlot, ItemStack>> getEquipment() {
        return Arrays.stream(EquipmentSlot.values()).map(slot -> new Pair<>(slot, this.getEquippedStack(slot))).collect(Collectors.toList());
    }

    public GameProfile getGameProfile() {
        return this.gameProfile;
    }

    /**
     * Sets the custom name
     * @param name new name to be set.
     */
    @Override
    public void setCustomName(Text name) {
        super.setCustomName(name);
        String profileName = "Taterzen";
        if(name != null) {
            profileName = name.getString();
            if(name.getString().length() > 16) {
                // Minecraft kicks you if player has name longer than 16 chars in GameProfile
                profileName = name.getString().substring(0, 16);
            }
        }
        CompoundTag skin = null;
        if(this.gameProfile != null)
            skin = this.writeSkinToTag(this.gameProfile);
        this.gameProfile = new GameProfile(this.getUuid(), profileName);
        if(skin != null) {
            this.setSkinFromTag(skin);
            if(DISGUISELIB_LOADED) {
                DisguiseLibCompatibility.setGameProfile(this, this.gameProfile);
            } else
                this.sendProfileUpdates();
        }
    }

    /**
     * Updates Taterzen's {@link GameProfile} for others.
     */
    public void sendProfileUpdates() {
        PlayerListS2CPacket packet = new PlayerListS2CPacket();
        //noinspection ConstantConditions
        PlayerListS2CPacketAccessor accessor = (PlayerListS2CPacketAccessor) packet;
        accessor.setEntries(Collections.singletonList(packet.new Entry(this.gameProfile, 0, GameMode.SURVIVAL, this.getName())));

        accessor.setAction(REMOVE_PLAYER);
        playerManager.sendToAll(packet);
        accessor.setAction(ADD_PLAYER);
        playerManager.sendToAll(packet);

        ServerChunkManager manager = (ServerChunkManager) this.world.getChunkManager();
        ThreadedAnvilChunkStorage storage = manager.threadedAnvilChunkStorage;
        EntityTrackerEntryAccessor trackerEntry = ((ThreadedAnvilChunkStorageAccessor) storage).getEntityTrackers().get(this.getEntityId());
        if(trackerEntry != null)
            trackerEntry.getTrackingPlayers().forEach(tracking -> trackerEntry.getEntry().startTracking(tracking));
    }


    /**
     * Applies skin from {@link GameProfile}.
     *
     * @param texturesProfile GameProfile containing textures.
     */
    public void applySkin(GameProfile texturesProfile) {
        if(this.gameProfile == null)
            return;

        // Setting new skin
        setSkinFromTag(writeSkinToTag(texturesProfile));

        if(DISGUISELIB_LOADED) {
            DisguiseLibCompatibility.setGameProfile(this, this.gameProfile);
        }

        // Sending updates
        this.sendProfileUpdates();
    }

    /**
     * Sets the Taterzen skin from tag
     * @param tag compound tag containing the skin
     */
    public void setSkinFromTag(CompoundTag tag) {
        // Clearing current skin
        try {
            PropertyMap map = this.gameProfile.getProperties();
            Property skin = map.get("textures").iterator().next();
            map.remove("textures", skin);
        } catch (NoSuchElementException ignored) { }
        // Setting the skin
        try {
            String value = tag.getString("value");
            String signature = tag.getString("signature");

            if(!value.isEmpty() && !signature.isEmpty()) {
                PropertyMap propertyMap = this.gameProfile.getProperties();
                propertyMap.put("textures", new Property("textures", value, signature));
            }

        } catch (Error ignored) { }
    }

    /**
     * Writes skin to tag
     * @param profile game profile containing skin
     *
     * @return compound tag with skin values
     */
    public CompoundTag writeSkinToTag(GameProfile profile) {
        CompoundTag skinTag = new CompoundTag();
        try {
            PropertyMap propertyMap = profile.getProperties();
            Property skin = propertyMap.get("textures").iterator().next();

            skinTag.putString("value", skin.getValue());
            skinTag.putString("signature", skin.getSignature());
        } catch (NoSuchElementException ignored) { }

        return skinTag;
    }
    /**
     * Loads Taterzen from {@link CompoundTag}.
     * @param tag tag to load Taterzen from.
     */
    @Override
    public void readCustomDataFromTag(CompoundTag tag) {
        super.readCustomDataFromTag(tag);
        CompoundTag npcTag = tag.getCompound("TaterzenNPCTag");

        this.npcData.hostile = npcTag.getBoolean("hostile");
        this.npcData.movement = NPCData.Movement.valueOf(npcTag.getString("movement"));

        this.npcData.leashable = npcTag.getBoolean("leashable");
        this.npcData.pushable = npcTag.getBoolean("pushable");

        // Compatibility (transition to disguiselib) & presets
        if(DISGUISELIB_LOADED && npcTag.contains("entityType")) {
            Identifier identifier = new Identifier(npcTag.getString("entityType"));
            DisguiseLibCompatibility.disguiseAs(this, Registry.ENTITY_TYPE.get(identifier));
        }

        // todo @deprecated (migration to more commands)
        if(npcTag.contains("command"))
            this.npcData.commands.add(npcTag.getString("command"));

        // Multiple commands
        ListTag commands = (ListTag) npcTag.get("Commands");
        if(commands != null) {
            commands.forEach(cmdTag -> {
                this.npcData.commands.add(cmdTag.asString());
            });
        }

        ListTag pathTargets = (ListTag) npcTag.get("PathTargets");
        if(pathTargets != null) {
            if(pathTargets.size() > 0) {
                pathTargets.forEach(posTag -> {
                    if(posTag instanceof CompoundTag) {
                        CompoundTag pos = (CompoundTag) posTag;
                        BlockPos target = new BlockPos(pos.getInt("x"), pos.getInt("y"), pos.getInt("z"));
                        this.npcData.pathTargets.add(target);
                    }
                });
                this.setPositionTarget(this.npcData.pathTargets.get(0), 1);
            }
        }

        ListTag messages = (ListTag) npcTag.get("Messages");
        if(messages != null && messages.size() > 0) {
            messages.forEach(msgTag -> {
                CompoundTag msgCompound = (CompoundTag) msgTag;
                this.npcData.messages.add(new Pair<>(TextUtil.fromTag(msgCompound.get("Message")), msgCompound.getInt("Delay")));
            });
        }

        this.npcData.permissionLevel = npcTag.getInt("PermissionLevel");


        this.gameProfile = new GameProfile(this.getUuid(), this.getDisplayName().asString());
        if(DISGUISELIB_LOADED) {
            DisguiseLibCompatibility.setGameProfile(this, this.gameProfile);
        }

        // Skin is cached
        CompoundTag skinTag = npcTag.getCompound("skin");
        this.setSkinFromTag(skinTag);

        // Initialises movement
        this.setMovement(this.npcData.movement);
    }

    /**
     * Saves Taterzen to {@link CompoundTag tag}.
     *
     * @param tag tag to save Taterzen to.
     */
    @Override
    public void writeCustomDataToTag(CompoundTag tag) {
        super.writeCustomDataToTag(tag);

        CompoundTag npcTag = new CompoundTag();

        npcTag.putBoolean("hostile", this.npcData.hostile);

        npcTag.putString("movement", this.npcData.movement.toString());

        npcTag.putBoolean("leashable", this.npcData.leashable);
        npcTag.putBoolean("pushable", this.npcData.pushable);

        // Commands
        ListTag commands = new ListTag();
        this.npcData.commands.forEach(cmd -> {
            commands.add(StringTag.of(cmd));
        });
        npcTag.put("Commands", commands);

        npcTag.put("skin", writeSkinToTag(this.gameProfile));

        ListTag pathTargets = new ListTag();
        this.npcData.pathTargets.forEach(blockPos -> {
            CompoundTag pos = new CompoundTag();
            pos.putInt("x", blockPos.getX());
            pos.putInt("y", blockPos.getY());
            pos.putInt("z", blockPos.getZ());
            pathTargets.add(pos);
        });
        npcTag.put("PathTargets", pathTargets);

        // Messages
        ListTag messages = new ListTag();
        this.npcData.messages.forEach(pair -> {
            CompoundTag msg = new CompoundTag();
            msg.put("Message", TextUtil.toTag(pair.getFirst()));
            msg.putInt("Delay", pair.getSecond());
            messages.add(msg);
        });
        npcTag.put("Messages", messages);

        npcTag.putInt("PermissionLevel", this.npcData.permissionLevel);

        tag.put("TaterzenNPCTag", npcTag);
    }

    /**
     * Sets player as equipment editor.
     * @param player player that will be marked as equipment editor.
     */
    public void setEquipmentEditor(@Nullable PlayerEntity player) {
        this.npcData.equipmentEditor = player;
    }

    /**
     * Sets player as equipment editor.
     * @param player player to check.
     * @return true if player is equipment editor of the NPC, otherwise false.
     */
    public boolean isEquipmentEditor(@NotNull PlayerEntity player) {
        return player.equals(this.npcData.equipmentEditor);
    }

    /**
     * Handles interaction (right clicking on the NPC).
     * @param player player interacting with NPC
     * @param pos interaction pos
     * @param hand player's interacting hand
     * @return {@link ActionResult#PASS} if NPC has a right click action, otherwise {@link ActionResult#FAIL}
     */
    @Override
    public ActionResult interactAt(PlayerEntity player, Vec3d pos, Hand hand) {
        long lastAction = ((ServerPlayerEntity) player).getLastActionTime();
        ActionResult result = ActionResult.FAIL;

        // As weird as it sounds, this gets triggered twice, first time with the item stack player is holding
        // then with "air" if fake type is player / armor stand
        if(lastAction - ((TaterzenPlayer) player).getLastInteractionTime() < 50)
            return result;

        if(this.isEquipmentEditor(player)) {
            ItemStack stack = player.getStackInHand(hand);

            if (stack.isEmpty() && player.isSneaking()) {
                this.dropEquipment(DamageSource.player(player), 1, true);
            }
            else if(player.isSneaking()) {
                this.equipStack(EquipmentSlot.MAINHAND, stack);
            }
            else {
                this.equipLootStack(getPreferredEquipmentSlot(stack), stack);
            }
            result = ActionResult.PASS;
        }
        else if(!this.npcData.commands.isEmpty()) {
            this.npcData.commands.forEach(cmd -> {
                if(cmd.contains("--clicker--")) {
                    cmd = cmd.replaceAll("--clicker--", player.getGameProfile().getName());
                }
                this.server.getCommandManager().execute(this.getCommandSource(), cmd);
            });
            result = ActionResult.PASS;
        }

        ((TaterzenPlayer) player).setLastInteraction(lastAction);
        return result;
    }

    /**
     * Adds the message to taterzen's message list.
     * @param text message to add
     */
    public void addMessage(Text text) {
        this.npcData.messages.add(new Pair<>(text, config.messages.messageDelay));
    }

    /**
     * Edits the message from taterzen's message list at index.
     * @param index index of the message to edit
     * @param text new text message
     */
    public void editMessage(int index, Text text) {
        if(index >= 0 && index < this.npcData.messages.size())
            this.npcData.messages.set(index, new Pair<>(text, config.messages.messageDelay));
    }

    /**
     * Removes message at index.
     * @param index index of message to be removed.
     */
    public void removeMessage(int index) {
        if(index < this.npcData.messages.size())
            this.npcData.messages.remove(index);
    }

    /**
     * Sets message delay.
     *
     * @param index index of the message to change delay for.
     * @param delay new delay.
     */
    public void setMessageDelay(int index, int delay) {
        if(index < this.npcData.messages.size()) {
            this.npcData.messages.get(index).mapSecond(previous -> delay);
        }
    }

    public void clearMessages() {
        this.npcData.messages = new ArrayList<>();
    }

    /**
     * Gets {@link ArrayList} of {@link Pair}s of messages and their delays.
     * @return arraylist of pairs with texts and delays.
     */
    public ArrayList<Pair<Text, Integer>> getMessages() {
        return this.npcData.messages;
    }

    /**
     * Used for disabling pushing
     * @param entity colliding entity
     */
    @Override
    public void pushAwayFrom(Entity entity) {
        if(this.npcData.pushable) {
            super.pushAwayFrom(entity);
        }
    }

    /**
     * Used for disabling pushing
     * @param entity colliding entity
     */
    @Override
    protected void pushAway(Entity entity) {
        if(this.npcData.pushable) {
            super.pushAway(entity);
        }
    }

    /**
     * Handles received hits.
     *
     * @param attacker entity that attacked NPC.
     * @return true if attack should be cancelled.
     */
    @Override
    public boolean handleAttack(Entity attacker) {
        if(attacker instanceof PlayerEntity && this.isEquipmentEditor((PlayerEntity) attacker)) {
            ItemStack main = this.getMainHandStack();
            this.setStackInHand(Hand.MAIN_HAND, this.getOffHandStack());
            this.setStackInHand(Hand.OFF_HAND, main);
            return true;
        }
        return this.npcData.movement == NPCData.Movement.LOOK || this.npcData.movement == NPCData.Movement.NONE || super.handleAttack(attacker);
    }


    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        //this.dataTracker.startTracking(PLAYER_MODEL_PARTS, (byte) 0x7f); //todo
    }

    @Override
    protected boolean isAffectedByDaylight() {
        return false;
    }

    @Override
    protected boolean isDisallowedInPeaceful() {
        return false;
    }

    @Override
    public boolean canBeLeashedBy(PlayerEntity player) {
        return !this.isLeashed() && this.npcData.leashable;
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(2, new LongDoorInteractGoal(this, true));
    }

    /**
     * Handles death of NPC.
     * @param source damage source responsible for death.
     */
    @Override
    public void onDeath(DamageSource source) {
        PlayerListS2CPacket playerListS2CPacket = new PlayerListS2CPacket();
        ((PlayerListS2CPacketAccessor) playerListS2CPacket).setAction(REMOVE_PLAYER);
        ((PlayerListS2CPacketAccessor) playerListS2CPacket).setEntries(Collections.singletonList(playerListS2CPacket.new Entry(this.gameProfile, 0, GameMode.SURVIVAL, new LiteralText(this.getName().asString()))));
        this.playerManager.sendToAll(playerListS2CPacket);
        super.onDeath(source);
    }

    @Override
    public void remove() {
        super.remove();
        TATERZEN_NPCS.remove(this);
    }

    @Override
    public boolean canUseRangedWeapon(RangedWeaponItem weapon) {
        return this.npcData.hostile;
    }

    @Override
    protected void updateDespawnCounter() {
    }
    @Override
    public void setCharging(boolean charging) {

    }

    @Override
    public void shoot(LivingEntity target, ItemStack crossbow, ProjectileEntity projectile, float multiShotSpray) {
        //this.attack(target, 4.0F);
    }

    @Override
    public void postShoot() {
    }

    @Override
    public void attack(LivingEntity target, float pullProgress) {
    }

    @Override
    protected SoundEvent getAmbientSound() {
        if(config.defaults.ambientSound == null)
            return null;
        return new SoundEvent(new Identifier(config.defaults.ambientSound));
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        if(config.defaults.hurtSound == null)
            return null;
        return new SoundEvent(new Identifier(config.defaults.hurtSound));
    }

    @Override
    protected SoundEvent getDeathSound() {
        if(config.defaults.deathSound == null)
            return null;
        return new SoundEvent(new Identifier(config.defaults.deathSound));
    }

    @Override
    public float getPathfindingFavor(BlockPos pos, WorldView world) {
        return 0.0F;
    }

    @Override
    protected Text getDefaultName() {
        return new LiteralText(config.defaults.name);
    }

    @Nullable
    public PlayerEntity getFakePlayer() {
        return this.fakePlayer;
    }
}
