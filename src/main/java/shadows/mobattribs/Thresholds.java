package shadows.mobattribs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.google.common.collect.ImmutableList;

public class Thresholds {

	/**
	 * A sorted list of all threshold values, with the highest-kill values at the lowest index.
	 */
	private final List<Threshold> values;

	/**
	 * Creates a new Thresholds object.
	 * @param values The threshold values. They need not be externally sorted, as they will be sorted internally.
	 */
	public Thresholds(List<Threshold> values) {
		var copy = new ArrayList<>(values);
		copy.sort(Comparator.comparingInt(Threshold::kills));
		Collections.reverse(copy);
		this.values = ImmutableList.copyOf(copy);
	}

	/**
	 * Returns the current value, which is the damage value of the smallest threshold the number of kills is greater than.
	 * @param kills The number of entity kills.
	 * @return The appropriate damage modifier, based on kills.
	 */
	public double getCurrentValue(int kills) {
		for (Threshold threshold : values) {
			if (kills > threshold.kills) return threshold.dmg;
		}
		return 1;
	}

	public static Thresholds fromString(String s) {
		String[] split = s.split(",");
		List<Threshold> values = new ArrayList<>(split.length);
		for (String entry : split) {
			String[] kvPair = entry.split("\\|");
			int kills = Integer.parseInt(kvPair[0].trim());
			double dmg = Double.parseDouble(kvPair[1].trim());
			Threshold pair = new Threshold(kills, dmg);
			values.add(pair);
		}
		return new Thresholds(values);
	}

	public static record Threshold(int kills, double dmg) {

	}
}
