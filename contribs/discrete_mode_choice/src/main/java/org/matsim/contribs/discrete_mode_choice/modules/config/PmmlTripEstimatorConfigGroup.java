package org.matsim.contribs.discrete_mode_choice.modules.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config group for the PmmlTripEstimator: a choice model read from a PMML file
 * (e.g. estimated with Apollo and exported with apollo2pmml).
 *
 * @author nkuehnel
 */
public class PmmlTripEstimatorConfigGroup extends ComponentConfigGroup {
	public static final String PMML_FILE = "pmmlFile";
	public static final String MODE_MAPPING = "modeMapping";
	public static final String VARIABLE_ALIASES = "variableAliases";
	public static final String PARAMETERS = "parameters";

	private String pmmlFile = null;
	private Map<String, String> modeMapping = new LinkedHashMap<>();
	private Map<String, String> variableAliases = new LinkedHashMap<>();
	private Map<String, Double> parameters = new LinkedHashMap<>();

	public PmmlTripEstimatorConfigGroup(String componentType, String componentName) {
		super(componentType, componentName);
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = new HashMap<>();
		comments.put(PMML_FILE, "PMML file of the choice model (relative to the config file).");
		comments.put(MODE_MAPPING, "MATSim mode to PMML alternative, e.g. 'car:car, drt:pt'. "
				+ "Empty: every alternative of the model is the MATSim mode of the same name.");
		comments.put(VARIABLE_ALIASES, "PMML input to provider variable, e.g. 'tt_car:car.tt, distance_km:trip.beeline_km'. "
				+ "Inputs without alias are provided under their own name.");
		comments.put(PARAMETERS, "Coefficients of the PMML file replaced by other values, e.g. calibrated constants: "
				+ "'asc_pt:-0.42, asc_bike:-1.3'. Empty: the estimates in the file.");
		return comments;
	}

	@StringGetter(PMML_FILE)
	public String getPmmlFile() {
		return pmmlFile;
	}

	@StringSetter(PMML_FILE)
	public void setPmmlFile(String pmmlFile) {
		this.pmmlFile = pmmlFile;
	}

	public Map<String, String> getModeMapping() {
		return Collections.unmodifiableMap(modeMapping);
	}

	public void setModeMapping(Map<String, String> modeMapping) {
		this.modeMapping = new LinkedHashMap<>(modeMapping);
	}

	@StringGetter(MODE_MAPPING)
	public String getModeMappingAsString() {
		return format(modeMapping);
	}

	@StringSetter(MODE_MAPPING)
	public void setModeMappingAsString(String value) {
		this.modeMapping = parse(value, MODE_MAPPING);
	}

	public Map<String, String> getVariableAliases() {
		return Collections.unmodifiableMap(variableAliases);
	}

	public void setVariableAliases(Map<String, String> variableAliases) {
		this.variableAliases = new LinkedHashMap<>(variableAliases);
	}

	@StringGetter(VARIABLE_ALIASES)
	public String getVariableAliasesAsString() {
		return format(variableAliases);
	}

	@StringSetter(VARIABLE_ALIASES)
	public void setVariableAliasesAsString(String value) {
		this.variableAliases = parse(value, VARIABLE_ALIASES);
	}

	public Map<String, Double> getParameters() {
		return Collections.unmodifiableMap(parameters);
	}

	public void setParameters(Map<String, Double> parameters) {
		this.parameters = new LinkedHashMap<>(parameters);
	}

	@StringGetter(PARAMETERS)
	public String getParametersAsString() {
		Map<String, String> values = new LinkedHashMap<>();
		parameters.forEach((k, v) -> values.put(k, Double.toString(v)));
		return format(values);
	}

	@StringSetter(PARAMETERS)
	public void setParametersAsString(String value) {
		Map<String, Double> values = new LinkedHashMap<>();
		parse(value, PARAMETERS).forEach((k, v) -> {
			try {
				values.put(k, Double.parseDouble(v));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(PARAMETERS + ": not a number for " + k + ": " + v);
			}
		});
		this.parameters = values;
	}

	private static String format(Map<String, String> map) {
		StringBuilder builder = new StringBuilder();
		map.forEach((k, v) -> builder.append(builder.length() == 0 ? "" : ", ").append(k).append(':').append(v));
		return builder.toString();
	}

	private static Map<String, String> parse(String value, String option) {
		Map<String, String> map = new LinkedHashMap<>();
		if (value == null) {
			return map;
		}
		for (String entry : value.split(",")) {
			if (entry.isBlank()) {
				continue;
			}
			int colon = entry.lastIndexOf(':');
			if (colon <= 0 || colon == entry.length() - 1) {
				throw new IllegalArgumentException(option + ": expected 'key:value' but got '" + entry.trim() + "'");
			}
			String key = entry.substring(0, colon).trim();
			if (map.put(key, entry.substring(colon + 1).trim()) != null) {
				throw new IllegalArgumentException(option + ": '" + key + "' is given twice");
			}
		}
		return map;
	}
}
