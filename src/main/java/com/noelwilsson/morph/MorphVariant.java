package com.noelwilsson.morph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A morph: a mob type plus the NBT that makes this one look different from the default (a red sheep, a baby zombie,
 * a charged creeper). Only appearance keys are kept, and only where they differ from a freshly made mob, so an
 * ordinary white sheep is {} and two red sheep killed on different days are the same morph.
 */
public record MorphVariant(Identifier id, CompoundTag data) {
	/** Older saves stored just the mob id; those read back as the default variant. */
	public static final Codec<MorphVariant> CODEC = Codec.withAlternative(
		RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.fieldOf("type").forGetter(MorphVariant::id),
			CompoundTag.CODEC.optionalFieldOf("data", new CompoundTag()).forGetter(MorphVariant::data)
		).apply(instance, MorphVariant::new)),
		Identifier.CODEC.xmap(MorphVariant::new, MorphVariant::id));
	public static final StreamCodec<ByteBuf, MorphVariant> STREAM_CODEC = StreamCodec.composite(
		Identifier.STREAM_CODEC, MorphVariant::id,
		ByteBufCodecs.COMPOUND_TAG, MorphVariant::data,
		MorphVariant::new);

	/** Saved keys that change how a mob looks. Everything else (health, position, trades, brain) is noise. */
	private static final Set<String> APPEARANCE_KEYS = Set.of(
		"Color", "Sheared", "sheared", "variant", "Variant", "Type", "type", "RabbitType", "MainGene", "HiddenGene",
		"VillagerData", "powered", "Size", "size", "Pumpkin", "HasLeftHorn", "HasRightHorn", "weather_state",
		"carriedBlockState", "IsBaby", "Age", "ChestedHorse");
	/** Name tags with a vanilla easter egg: rainbow sheep, upside-down mobs, the Toast rabbit, Johnny. */
	private static final Set<String> SPECIAL_NAMES = Set.of("jeb_", "Dinnerbone", "Grumm", "Toast", "Johnny");
	private static final int BABY_AGE = -24000;
	/** Components that name a numeric variant key ("Color": 14 is red, "Variant": 3 is a chestnut horse). */
	private static final List<DataComponentType<?>> NUMERIC_VARIANTS = List.of(
		DataComponents.SHEEP_COLOR, DataComponents.SHULKER_COLOR, DataComponents.RABBIT_VARIANT, DataComponents.HORSE_VARIANT,
		DataComponents.TROPICAL_FISH_BASE_COLOR, DataComponents.TROPICAL_FISH_PATTERN, DataComponents.TROPICAL_FISH_PATTERN_COLOR);

	public MorphVariant {
		data = data.copy();
	}

	private MorphVariant(Identifier id) {
		this(id, new CompoundTag());
	}

	/** The default look of a mob type. */
	public static MorphVariant of(EntityType<?> type) {
		return new MorphVariant(BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	/** How this mob looks right now. */
	public static MorphVariant of(LivingEntity entity) {
		CompoundTag data = appearance(entity);
		LivingEntity template = MorphTemplates.get(entity.getType(), entity.level());
		if (template != null) {
			CompoundTag defaults = appearance(template);
			data.keySet().removeIf(key -> data.get(key).equals(defaults.get(key)));
		}
		return new MorphVariant(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), data);
	}

	/**
	 * Normalizes hand-written NBT (from a command) the same way a kill does, so {Color:14} and {Color:14b,Health:5f}
	 * both become the red sheep the player unlocked. Null if the mob can't be made.
	 */
	public static @Nullable MorphVariant parse(EntityType<?> type, CompoundTag data, Level level) {
		LivingEntity entity = create(type, data, level);
		return entity == null ? null : of(entity);
	}

	public @Nullable EntityType<?> type() {
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
	}

	public boolean isDefault() {
		return data.isEmpty();
	}

	/** A fresh, never-spawned mob of this type with this look applied. */
	public static @Nullable LivingEntity create(EntityType<?> type, CompoundTag data, Level level) {
		Entity created = type.create(level, EntitySpawnReason.LOAD);
		if (!(created instanceof LivingEntity living)) {
			return null;
		}
		apply(living, data);
		return living;
	}

	/** Loads the look onto an entity. The entity is never in a level, so the defaults load() fills in are harmless. */
	public static void apply(Entity entity, CompoundTag data) {
		if (!data.isEmpty()) {
			entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.level().registryAccess(), data));
		}
	}

	/** "red, sheared", or "" for the default look. */
	public String describe(Level level) {
		EntityType<?> type = type();
		LivingEntity entity = type == null ? null : create(type, data, level);
		List<String> words = new ArrayList<>();
		for (String key : data.keySet()) {
			String word = describe(key, data.get(key), entity);
			if (!word.isEmpty() && !words.contains(word)) {
				words.add(word);
			}
		}
		return String.join(", ", words);
	}

	/** The NBT a command takes to pick this variant, e.g. {Color:14b}. */
	public String snbt() {
		return data.toString();
	}

	private static CompoundTag appearance(LivingEntity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.level().registryAccess());
		entity.saveWithoutId(output);
		CompoundTag saved = output.buildResult();
		CompoundTag data = new CompoundTag();
		for (String key : saved.keySet()) {
			if (APPEARANCE_KEYS.contains(key)) {
				data.put(key, saved.get(key).copy());
			}
		}
		// Age counts up to adulthood and down after breeding: only "is a baby" is a look.
		data.getInt("Age").ifPresent(age -> data.putInt("Age", age < 0 ? BABY_AGE : 0));
		// Level only adds a badge, and would split every villager profession into five morphs.
		data.getCompound("VillagerData").ifPresent(villager -> villager.remove("level"));
		if (entity.getCustomName() != null && SPECIAL_NAMES.contains(entity.getCustomName().getString())) {
			data.putString("CustomName", entity.getCustomName().getString());
		}
		return data;
	}

	private static String describe(String key, Tag value, @Nullable LivingEntity entity) {
		switch (key) {
			case "Age", "IsBaby" -> {
				return "baby";
			}
			case "CustomName" -> {
				return "\"" + value.asString().orElse("") + "\"";
			}
			case "Size", "size" -> {
				return "size " + value.asNumber().map(Number::intValue).orElse(0);
			}
			case "VillagerData" -> {
				if (!(value instanceof CompoundTag villager)) {
					return "";
				}
				String profession = words(villager.getStringOr("profession", "none"));
				String biome = words(villager.getStringOr("type", ""));
				return profession.equals("none") ? biome : profession + " " + biome;
			}
			case "carriedBlockState" -> {
				return value instanceof CompoundTag block ? "holding " + words(block.getStringOr("Name", "")) : "";
			}
			default -> {
			}
		}
		if (value instanceof StringTag string) {
			return words(string.value());
		}
		// A variant stored as a number: ask the mob what it means.
		if (value instanceof NumericTag && !isFlag(key) && entity != null) {
			List<String> names = new ArrayList<>();
			for (DataComponentType<?> component : NUMERIC_VARIANTS) {
				if (entity.get(component) instanceof StringRepresentable named) {
					names.add(words(named.getSerializedName()));
				}
			}
			if (!names.isEmpty()) {
				return String.join(" ", names);
			}
		}
		if (isFlag(key)) {
			String flag = switch (key) {
				case "powered" -> "charged";
				case "ChestedHorse" -> "chest";
				default -> words(key.replaceAll("([a-z])([A-Z])", "$1 $2").replace("Has ", ""));
			};
			return value.asBoolean().orElse(false) ? flag : "no " + flag;
		}
		return words(key) + " " + value;
	}

	private static boolean isFlag(String key) {
		return Set.of("Sheared", "sheared", "powered", "Pumpkin", "HasLeftHorn", "HasRightHorn", "ChestedHorse").contains(key);
	}

	/** "minecraft:pale_garden" to "pale garden". */
	private static String words(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		return path.replace('_', ' ').toLowerCase(Locale.ROOT);
	}
}
