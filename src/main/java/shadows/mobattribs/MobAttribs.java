package shadows.mobattribs;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegistryEvent.Register;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import shadows.placebo.config.Configuration;
import shadows.placebo.util.RunnableReloader;

@Mod(MobAttribs.MODID)
public class MobAttribs {

	public static final String MODID = "mobattribs";
	public static final Logger LOGGER = LogManager.getLogger(MODID);
	public static final Map<EntityType<?>, RangedAttribute> DMG_ATTRIBS = new IdentityHashMap<>();
	public static final Map<EntityType<?>, UUID> DMG_MODIF_IDS = new IdentityHashMap<>();
	public static final Map<EntityType<?>, Thresholds> THRESHOLDS = new IdentityHashMap<>();
	public static Thresholds defaultThresholds;

	public MobAttribs() {
		FMLJavaModLoadingContext.get().getModEventBus().register(this);
		MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, this::dmgHandler);
		MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, this::kill);
		MinecraftForge.EVENT_BUS.addListener(this::reloads);
	}

	@SubscribeEvent
	public void regAttribs(Register<RecipeSerializer<?>> e) {
		((ForgeRegistry<Attribute>) ForgeRegistries.ATTRIBUTES).unfreeze(); // just a little bit of crime...
		Random rand = new Random();
		for (EntityType<?> type : ForgeRegistries.ENTITIES.getValues()) {
			String id = type.getRegistryName().toString().replace(':', '_');
			ResourceLocation newId = new ResourceLocation(MODID, id);
			RangedAttribute attrib = new RangedAttribute(newId.toString(), 1, 0, 1024);
			DMG_ATTRIBS.put(type, attrib);
			ForgeRegistries.ATTRIBUTES.register(attrib.setRegistryName(newId));
			rand.setSeed(id.hashCode());
			UUID uid = randomUUID(rand);
			DMG_MODIF_IDS.put(type, uid);
		}
		((ForgeRegistry<Attribute>) ForgeRegistries.ATTRIBUTES).freeze();
		loadConfig();
	}

	public void reloads(AddReloadListenerEvent e) {
		e.addListener(RunnableReloader.of(this::loadConfig));
	}

	private void loadConfig() {
		Configuration cfg = new Configuration(MODID);

		cfg.setTitle("Mob Damage Attributes Configuration");
		cfg.setComment("This file refers to \"Thresholds\", which take the string format of a comma-separated list: kills|value,kills|value,kills|value\nFor example, a thresholds list specifying 10% more damage at 20 kills, and 50% more damage at 500 kills would be: 20|1.10,500|1.50");

		String defStr = cfg.getString("Default Threshold", "_defaults", "", "The default threshold to apply to all entities if an entity-specific one is not specified. If no default is set, and no entity-specific one is set, an infinitely-scaling 3%/10 kills threshold will be used.");

		if (!defStr.isEmpty()) try {
			defaultThresholds = Thresholds.fromString(defStr);
		} catch (Exception ex) {
			ex.printStackTrace();
			LOGGER.error("Invalid default threshold value {} will be ignored.", defStr);
		}

		for (EntityType<?> t : DMG_ATTRIBS.keySet()) {
			ResourceLocation id = t.getRegistryName();
			String cat = id.getNamespace();
			String path = id.getPath();

			String thresh = cfg.getString(path, cat, "", "The thresholds for this entity.");
			if (!thresh.isEmpty()) {
				try {
					Thresholds threshold = Thresholds.fromString(thresh);
					THRESHOLDS.put(t, threshold);
				} catch (Exception ex) {
					ex.printStackTrace();
					LOGGER.error("Invalid threshold value {} will be ignored.", thresh);
				}
			}
		}
		if (cfg.hasChanged()) cfg.save();
	}

	public static UUID randomUUID(Random rand) {
		return new UUID(rand.nextLong(), rand.nextLong());
	}

	@SubscribeEvent
	public void modifAttribs(EntityAttributeModificationEvent e) {
		DMG_ATTRIBS.values().forEach(a -> e.add(EntityType.PLAYER, a, 1));
	}

	public void dmgHandler(LivingHurtEvent e) {
		if (e.getSource().getEntity() instanceof Player p) {
			RangedAttribute attrib = DMG_ATTRIBS.get(e.getEntity().getType());
			float modif = (float) p.getAttributeValue(attrib);
			e.setAmount(e.getAmount() * modif);
		}
	}

	public void kill(LivingDeathEvent e) {
		if (e.getSource().getEntity() instanceof ServerPlayer p) {
			EntityType<?> type = e.getEntityLiving().getType();
			double oldVal = p.getAttribute(DMG_ATTRIBS.get(type)).getBaseValue();
			int kills = p.getStats().getValue(Stats.ENTITY_KILLED.get(type)) + 1;
			Thresholds thresh = THRESHOLDS.get(type);

			if (thresh != null) {
				p.getAttribute(DMG_ATTRIBS.get(type)).setBaseValue(thresh.getCurrentValue(kills));
			} else if (defaultThresholds != null) {
				p.getAttribute(DMG_ATTRIBS.get(type)).setBaseValue(defaultThresholds.getCurrentValue(kills));
			} else {
				p.getAttribute(DMG_ATTRIBS.get(type)).setBaseValue(1 + (kills / 10 * 0.03));
			}

			double newVal = p.getAttribute(DMG_ATTRIBS.get(type)).getBaseValue();
			if (oldVal != newVal) {
				Component name = ((MutableComponent) type.getDescription()).withStyle(ChatFormatting.RED);
				Component val = new TextComponent(ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format((newVal - 1) * 100) + "%").withStyle(ChatFormatting.GOLD);
				Component specifier = new TranslatableComponent("mobattribs.msg." + (isVowel(name.getString().charAt(0)) ? "an" : "a"));
				p.sendMessage(new TranslatableComponent("mobattribs.msg.rank_up", val, specifier, name), Util.NIL_UUID);
				p.level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.NOTE_BLOCK_BELL, p.getSoundSource(), 1, 0.5F);
			}
		}
	}

	public static boolean isVowel(char c) {
		return "AEIOUaeiou".indexOf(c) != -1;
	}

}
