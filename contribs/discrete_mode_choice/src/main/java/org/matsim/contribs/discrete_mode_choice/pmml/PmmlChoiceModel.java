package org.matsim.contribs.discrete_mode_choice.pmml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.matsim.contribs.discrete_mode_choice.pmml.PmmlExpressions.Expression;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlExpressions.Frame;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * A discrete choice model read from PMML 4.4, evaluated without any third-party
 * library (only the JDK's XML parser).
 * <p>
 * Supported is the "choice-model profile" that
 * <a href="https://github.com/nkuehnel/apollo2pmml">apollo2pmml</a> writes for
 * models estimated with Apollo (R):
 * <ul>
 * <li>a {@code DataDictionary} whose numeric {@code DataField}s are the model
 * inputs, optionally with {@code Extension}s {@code unit} and
 * {@code description} (the variable contract a consumer has to fill),</li>
 * <li>a {@code TransformationDictionary} with the coefficients as derived
 * fields (marked with {@code <Extension name="parameter"/>}), helper variables
 * and the utilities {@code U(alt)},</li>
 * <li>a classification {@code RegressionModel} (normalization {@code simplemax},
 * {@code softmax} or {@code none}) with one {@code RegressionTable} per
 * alternative and the outputs {@code probability(alt)} and
 * {@code utility(alt)}.</li>
 * </ul>
 * Expressions may use {@code Constant}, {@code FieldRef} and {@code Apply} with
 * the PMML built-in functions listed in {@link #getSupportedFunctions()}.
 * Anything else is rejected with a {@link PmmlException} when the file is read,
 * never silently ignored.
 * <p>
 * The utility of an alternative only depends on the inputs it references
 * ({@link #getUtilityInputs(String)}), so a caller such as a MATSim trip
 * estimator only has to provide the attributes of the mode it evaluates.
 * Coefficients can be replaced without changing the file
 * ({@link #withParameters(Map)}), e.g. for calibrated constants.
 * <p>
 * Instances are immutable and thread-safe; the per-evaluation state lives in
 * {@link Inputs}, which is not thread-safe.
 * <p>
 * Nested logit: the expression layer is independent of the model structure, so
 * the probabilities of a nested logit written as derived fields evaluate as
 * they are. The nest structure itself (nests, scale parameters, membership)
 * needed by a nested-logit selector is not read yet.
 *
 * @author nkuehnel
 */
public final class PmmlChoiceModel {
	private static final String PARAMETER_EXTENSION = "parameter";
	private static final Set<String> NUMERIC_TYPES = Set.of("double", "float", "integer");
	private static final Set<String> MODEL_ELEMENTS = Set.of("AnomalyDetectionModel", "AssociationModel",
			"BayesianNetworkModel", "BaselineModel", "ClusteringModel", "GaussianProcessModel",
			"GeneralRegressionModel", "MiningModel", "NaiveBayesModel", "NearestNeighborModel", "NeuralNetwork",
			"RegressionModel", "RuleSetModel", "SequenceModel", "Scorecard", "SupportVectorMachineModel",
			"TextModel", "TimeSeriesModel", "TreeModel");

	private enum Normalization {
		none, simplemax, softmax
	}

	private record Predictor(int[] slots, double exponent, double coefficient) {
	}

	private record Table(double intercept, Predictor[] predictors) {
		double evaluate(Frame frame) {
			double value = intercept;
			for (Predictor predictor : predictors) {
				double term = predictor.coefficient;
				for (int slot : predictor.slots) {
					term *= predictor.exponent == 1.0 ? frame.get(slot) : Math.pow(frame.get(slot), predictor.exponent);
				}
				value += term;
			}
			return value;
		}
	}

	private final Document document;
	private final Map<String, Double> overrides;

	private final String description;
	private final List<String> alternatives;
	private final Map<String, Integer> alternativeIndex = new HashMap<>();
	private final List<PmmlVariable> variables = new ArrayList<>();
	private final Map<String, Integer> slots = new HashMap<>();
	private final String[] names;
	private final int numberOfInputs;
	private final Expression[] derived;
	private final Map<String, Double> parameters = new LinkedHashMap<>();
	private final Map<String, Expression> outputs = new LinkedHashMap<>();
	private final Expression[] utilities;
	private final BitSet[] utilityRoots;
	private final Table[] tables;
	private final Normalization normalization;

	/** per slot: the slots its expression refers to directly */
	private final int[][] dependencies;

	private PmmlChoiceModel(Document document, Map<String, Double> overrides) {
		this.document = document;
		this.overrides = Map.copyOf(overrides);

		Element pmml = document.getDocumentElement();
		if (!"PMML".equals(localName(pmml))) {
			throw new PmmlException("Not a PMML document: root element is " + localName(pmml));
		}

		Element header = child(pmml, "Header");
		this.description = header == null ? "" : header.getAttribute("description");

		Element model = findModel(pmml);
		if (!"classification".equals(model.getAttribute("functionName"))) {
			throw new PmmlException("RegressionModel must have functionName=\"classification\"");
		}
		String target = targetField(model);

		// I) Slots: inputs (numeric data fields) first, then derived fields
		List<String> slotNames = new ArrayList<>();
		Element dataDictionary = requiredChild(pmml, "DataDictionary");
		Map<String, Element> dataFields = new LinkedHashMap<>();
		for (Element field : children(dataDictionary, "DataField")) {
			String name = field.getAttribute("name");
			dataFields.put(name, field);
			if (name.equals(target) || !NUMERIC_TYPES.contains(field.getAttribute("dataType"))) {
				continue;
			}
			addSlot(name, slotNames);
			variables.add(new PmmlVariable(name, extension(field, "unit"), extension(field, "description")));
		}
		this.numberOfInputs = slotNames.size();

		List<Element> derivedFields = new ArrayList<>();
		Element transformations = child(pmml, "TransformationDictionary");
		if (transformations != null) {
			for (Element element : childElements(transformations)) {
				if (!"DerivedField".equals(localName(element))) {
					throw new PmmlException("Unsupported element in TransformationDictionary: " + localName(element));
				}
				derivedFields.add(element);
			}
		}
		Element localTransformations = child(model, "LocalTransformations");
		if (localTransformations != null) {
			derivedFields.addAll(children(localTransformations, "DerivedField"));
		}
		for (Element field : derivedFields) {
			addSlot(field.getAttribute("name"), slotNames);
		}
		for (String name : this.overrides.keySet()) {
			Integer slot = slots.get(name);
			if (slot == null || slot < numberOfInputs || !isParameter(derivedFields.get(slot - numberOfInputs))) {
				throw new PmmlException("Cannot override '" + name + "': not a parameter of the model");
			}
		}
		this.names = slotNames.toArray(String[]::new);

		// II) Compile the derived fields
		this.derived = new Expression[derivedFields.size()];
		this.dependencies = new int[names.length][];
		for (int i = 0; i < numberOfInputs; i++) {
			dependencies[i] = new int[0];
		}
		for (int i = 0; i < derivedFields.size(); i++) {
			Element field = derivedFields.get(i);
			String name = field.getAttribute("name");
			int slot = numberOfInputs + i;
			BitSet used = new BitSet();
			if (isParameter(field)) {
				double value = this.overrides.containsKey(name) ? this.overrides.get(name)
						: constantValue(expressionElement(field), name);
				parameters.put(name, value);
				derived[i] = new PmmlExpressions.Constant(value);
			} else if (!NUMERIC_TYPES.contains(field.getAttribute("dataType"))) {
				derived[i] = new PmmlExpressions.Unsupported(
						"Derived field '" + name + "' has dataType " + field.getAttribute("dataType") + "; only numeric fields are evaluated");
			} else {
				derived[i] = compile(expressionElement(field), used, dataFields, name);
			}
			dependencies[slot] = used.stream().toArray();
		}
		checkForCycles();

		// III) Model: regression tables, outputs, utilities
		this.normalization = normalization(model);
		List<String> alternatives = new ArrayList<>();
		List<Table> tables = new ArrayList<>();
		for (Element element : childElements(model)) {
			String name = localName(element);
			if (name.equals("RegressionTable")) {
				String category = element.getAttribute("targetCategory");
				if (category.isEmpty()) {
					throw new PmmlException("RegressionTable without targetCategory");
				}
				alternativeIndex.put(category, alternatives.size());
				alternatives.add(category);
				tables.add(table(element, dataFields));
			} else if (!Set.of("Extension", "MiningSchema", "Output", "ModelStats", "ModelExplanation", "Targets",
					"LocalTransformations", "ModelVerification").contains(name)) {
				throw new PmmlException("Unsupported element in RegressionModel: " + name);
			}
		}
		if (alternatives.isEmpty()) {
			throw new PmmlException("RegressionModel without RegressionTable");
		}
		this.alternatives = List.copyOf(alternatives);
		this.tables = tables.toArray(Table[]::new);

		this.utilities = new Expression[alternatives.size()];
		this.utilityRoots = new BitSet[alternatives.size()];
		Element output = child(model, "Output");
		if (output != null) {
			for (Element field : children(output, "OutputField")) {
				String name = field.getAttribute("name");
				if ("probability".equals(field.getAttribute("feature"))) {
					continue; // computed from the regression tables
				}
				Element expression = expressionElement(field);
				if (expression == null) {
					continue; // e.g. predictedValue: not needed here
				}
				BitSet used = new BitSet();
				Expression compiled = compile(expression, used, dataFields, name);
				outputs.put(name, compiled);
				if (name.startsWith("utility(") && name.endsWith(")")) {
					Integer index = alternativeIndex.get(name.substring(8, name.length() - 1));
					if (index != null) {
						utilities[index] = compiled;
						utilityRoots[index] = used;
					}
				}
			}
		}
		if (normalization == Normalization.softmax) {
			// log-linear model: the linear predictor is the utility, unless the file states it explicitly
			for (int i = 0; i < utilities.length; i++) {
				if (utilities[i] == null) {
					Table table = this.tables[i];
					utilities[i] = table::evaluate;
					utilityRoots[i] = new BitSet();
					for (Predictor predictor : table.predictors) {
						for (int slot : predictor.slots) {
							utilityRoots[i].set(slot);
						}
					}
				}
			}
		}
	}

	// --- reading ---

	public static PmmlChoiceModel read(Path path) {
		try (InputStream stream = Files.newInputStream(path)) {
			return read(stream);
		} catch (IOException e) {
			throw new PmmlException("Cannot read PMML file " + path, e);
		}
	}

	public static PmmlChoiceModel read(URL url) {
		try (InputStream stream = url.openStream()) {
			return read(stream);
		} catch (IOException e) {
			throw new PmmlException("Cannot read PMML file " + url, e);
		}
	}

	public static PmmlChoiceModel read(InputStream stream) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return new PmmlChoiceModel(builder.parse(stream), Map.of());
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new PmmlException("Cannot parse PMML", e);
		}
	}

	/**
	 * A copy of this model in which the given coefficients (parameters of the
	 * PMML file) have other values, e.g. calibrated alternative-specific
	 * constants. Unknown names are rejected.
	 */
	public PmmlChoiceModel withParameters(Map<String, Double> values) {
		Map<String, Double> merged = new HashMap<>(overrides);
		merged.putAll(values);
		return new PmmlChoiceModel(document, merged);
	}

	// --- model information ---

	public String getDescription() {
		return description;
	}

	public List<String> getAlternatives() {
		return alternatives;
	}

	/** all inputs of the model (the variable contract) */
	public List<PmmlVariable> getVariables() {
		return Collections.unmodifiableList(variables);
	}

	public Optional<PmmlVariable> getVariable(String name) {
		return variables.stream().filter(v -> v.name().equals(name)).findFirst();
	}

	/** coefficients by name, as estimated or as overridden */
	public Map<String, Double> getParameters() {
		return Collections.unmodifiableMap(parameters);
	}

	/** whether {@link #getUtility(String, Inputs)} is defined for every alternative */
	public boolean hasUtilities() {
		return Arrays.stream(utilities).allMatch(u -> u != null);
	}

	/** the inputs the utility of an alternative depends on */
	public Set<String> getUtilityInputs(String alternative) {
		Expression utility = utilities[index(alternative)];
		if (utility == null) {
			throw new PmmlException("The model has no utility output for alternative '" + alternative + "'");
		}
		return inputsReachableFrom(utilityRoots[index(alternative)]);
	}

	/** the inputs the choice probabilities depend on */
	public Set<String> getProbabilityInputs() {
		BitSet roots = new BitSet();
		for (Table table : tables) {
			for (Predictor predictor : table.predictors) {
				for (int slot : predictor.slots) {
					roots.set(slot);
				}
			}
		}
		return inputsReachableFrom(roots);
	}

	public static List<String> getSupportedFunctions() {
		return PmmlExpressions.SUPPORTED_FUNCTIONS;
	}

	// --- evaluation ---

	/** a fresh, empty set of inputs for this model */
	public Inputs newInputs() {
		return new Inputs(this);
	}

	public double getUtility(String alternative, Inputs inputs) {
		return getUtility(index(alternative), inputs);
	}

	public double getUtility(int alternative, Inputs inputs) {
		Expression utility = utilities[alternative];
		if (utility == null) {
			throw new PmmlException("The model has no utility output for alternative '" + alternatives.get(alternative) + "'");
		}
		return utility.evaluate(inputs.frameFor(this));
	}

	/** utilities in the order of {@link #getAlternatives()} */
	public double[] getUtilities(Inputs inputs) {
		double[] values = new double[utilities.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = getUtility(i, inputs);
		}
		return values;
	}

	/** choice probabilities in the order of {@link #getAlternatives()} */
	public double[] getProbabilities(Inputs inputs) {
		Frame frame = inputs.frameFor(this);
		double[] y = new double[tables.length];
		for (int i = 0; i < y.length; i++) {
			y[i] = tables[i].evaluate(frame);
		}
		switch (normalization) {
		case none:
			return y;
		case simplemax: {
			double sum = Arrays.stream(y).sum();
			for (int i = 0; i < y.length; i++) {
				y[i] /= sum;
			}
			return y;
		}
		case softmax: {
			double max = Arrays.stream(y).max().getAsDouble();
			double sum = 0.0;
			for (int i = 0; i < y.length; i++) {
				y[i] = Math.exp(y[i] - max);
				sum += y[i];
			}
			for (int i = 0; i < y.length; i++) {
				y[i] /= sum;
			}
			return y;
		}
		default:
			throw new IllegalStateException();
		}
	}

	/** value of any numeric derived field or output field, e.g. a helper variable or a logsum */
	public double evaluate(String field, Inputs inputs) {
		Expression output = outputs.get(field);
		Frame frame = inputs.frameFor(this);
		if (output != null) {
			return output.evaluate(frame);
		}
		Integer slot = slots.get(field);
		if (slot == null) {
			throw new PmmlException("Unknown field '" + field + "'");
		}
		return frame.get(slot);
	}

	/**
	 * Input values of one evaluation. Every input that the evaluated expression
	 * refers to must be set; a missing input raises a {@link PmmlException}
	 * instead of being replaced by a default. Not thread-safe.
	 */
	public static final class Inputs {
		private final PmmlChoiceModel model;
		private final Frame frame;

		private Inputs(PmmlChoiceModel model) {
			this.model = model;
			this.frame = new Frame(model.numberOfInputs, model.derived, model.names);
		}

		public Inputs set(String name, double value) {
			Integer slot = model.slots.get(name);
			if (slot == null || slot >= model.numberOfInputs) {
				throw new PmmlException("'" + name + "' is not an input of the model");
			}
			frame.values[slot] = value;
			frame.known[slot] = true;
			return this;
		}

		public boolean isSet(String name) {
			Integer slot = model.slots.get(name);
			return slot != null && slot < model.numberOfInputs && frame.known[slot];
		}

		/** removes all input values */
		public void clear() {
			Arrays.fill(frame.known, false);
		}

		private Frame frameFor(PmmlChoiceModel caller) {
			if (caller != model) {
				throw new PmmlException("Inputs were created for another model");
			}
			frame.resetDerived();
			return frame;
		}
	}

	// --- compilation ---

	private Expression compile(Element element, BitSet used, Map<String, Element> dataFields, String context) {
		if (element == null) {
			throw new PmmlException("Field '" + context + "' has no expression");
		}
		String name = localName(element);
		switch (name) {
		case "Constant":
			return new PmmlExpressions.Constant(constantValue(element, context));
		case "FieldRef": {
			int slot = resolve(element.getAttribute("field"), dataFields, context);
			used.set(slot);
			return new PmmlExpressions.FieldRef(slot);
		}
		case "Apply":
			return compileApply(element, used, dataFields, context);
		default:
			throw new PmmlException("Unsupported expression <" + name + "> in '" + context + "'");
		}
	}

	private Expression compileApply(Element apply, BitSet used, Map<String, Element> dataFields, String context) {
		String function = apply.getAttribute("function");
		if (apply.hasAttribute("mapMissingTo") || apply.hasAttribute("defaultValue")) {
			throw new PmmlException("Apply with mapMissingTo/defaultValue is not supported (in '" + context + "')");
		}
		List<Element> argumentElements = childElements(apply).stream().filter(e -> !"Extension".equals(localName(e))).toList();

		if (function.equals("isMissing") || function.equals("isNotMissing")) {
			requireArity(function, argumentElements, 1, 1, context);
			Element argument = argumentElements.get(0);
			if (!"FieldRef".equals(localName(argument))) {
				throw new PmmlException(function + " is only supported on a FieldRef (in '" + context + "')");
			}
			int slot = resolve(argument.getAttribute("field"), dataFields, context);
			used.set(slot);
			return new PmmlExpressions.IsMissing(slot, function.equals("isMissing"));
		}

		Expression[] arguments = new Expression[argumentElements.size()];
		for (int i = 0; i < arguments.length; i++) {
			arguments[i] = compile(argumentElements.get(i), used, dataFields, context);
		}

		if (PmmlExpressions.UNARY.containsKey(function)) {
			requireArity(function, argumentElements, 1, 1, context);
			return new PmmlExpressions.Unary(PmmlExpressions.UNARY.get(function), arguments[0]);
		}
		if (function.equals("+") || function.equals("*")) { // PMML: two arguments; more are accepted
			requireArity(function, argumentElements, 2, Integer.MAX_VALUE, context);
			if (arguments.length > 2) {
				return new PmmlExpressions.Reduce(PmmlExpressions.REDUCE.get(function.equals("+") ? "sum" : "product"),
						arguments);
			}
		}
		if (PmmlExpressions.BINARY.containsKey(function)) {
			requireArity(function, argumentElements, 2, 2, context);
			return new PmmlExpressions.Binary(PmmlExpressions.BINARY.get(function), arguments[0], arguments[1]);
		}
		if (PmmlExpressions.REDUCE.containsKey(function)) {
			requireArity(function, argumentElements, 1, Integer.MAX_VALUE, context);
			return new PmmlExpressions.Reduce(PmmlExpressions.REDUCE.get(function), arguments);
		}
		switch (function) {
		case "avg":
			requireArity(function, argumentElements, 1, Integer.MAX_VALUE, context);
			return new PmmlExpressions.Average(arguments);
		case "if":
			requireArity(function, argumentElements, 2, 3, context);
			return new PmmlExpressions.If(arguments[0], arguments[1], arguments.length == 3 ? arguments[2] : null);
		case "and":
		case "or":
			requireArity(function, argumentElements, 2, Integer.MAX_VALUE, context);
			return new PmmlExpressions.Logical(function.equals("and"), arguments);
		default:
			throw new PmmlException("Unsupported function '" + function + "' in '" + context + "'; supported are "
					+ PmmlExpressions.SUPPORTED_FUNCTIONS);
		}
	}

	private static void requireArity(String function, List<Element> arguments, int min, int max, String context) {
		if (arguments.size() < min || arguments.size() > max) {
			throw new PmmlException(String.format("Function '%s' with %d arguments in '%s'", function, arguments.size(), context));
		}
	}

	private int resolve(String field, Map<String, Element> dataFields, String context) {
		Integer slot = slots.get(field);
		if (slot == null) {
			if (dataFields.containsKey(field)) {
				throw new PmmlException("Field '" + field + "' (used in '" + context + "') is not numeric");
			}
			throw new PmmlException("Unknown field '" + field + "' in '" + context + "'");
		}
		return slot;
	}

	private static double constantValue(Element constant, String context) {
		if (constant == null || !"Constant".equals(localName(constant))) {
			throw new PmmlException("Parameter '" + context + "' must be a Constant");
		}
		if ("true".equals(constant.getAttribute("missing"))) {
			throw new PmmlException("Missing constants are not supported (in '" + context + "')");
		}
		String text = constant.getTextContent().trim();
		String type = constant.getAttribute("dataType");
		if (type.equals("boolean")) {
			return Boolean.parseBoolean(text) ? 1.0 : 0.0;
		}
		if (!type.isEmpty() && !NUMERIC_TYPES.contains(type)) {
			throw new PmmlException("Constant of type " + type + " is not supported (in '" + context + "')");
		}
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException e) {
			throw new PmmlException("Not a number: '" + text + "' (in '" + context + "')");
		}
	}

	private Table table(Element element, Map<String, Element> dataFields) {
		String context = "RegressionTable " + element.getAttribute("targetCategory");
		double intercept = number(element.getAttribute("intercept"), 0.0, context);
		List<Predictor> predictors = new ArrayList<>();
		for (Element predictor : childElements(element)) {
			switch (localName(predictor)) {
			case "NumericPredictor":
				predictors.add(new Predictor(new int[] { resolve(predictor.getAttribute("name"), dataFields, context) },
						number(predictor.getAttribute("exponent"), 1.0, context),
						number(predictor.getAttribute("coefficient"), Double.NaN, context)));
				break;
			case "PredictorTerm":
				int[] fields = children(predictor, "FieldRef").stream()
						.mapToInt(f -> resolve(f.getAttribute("field"), dataFields, context)).toArray();
				predictors.add(new Predictor(fields, 1.0, number(predictor.getAttribute("coefficient"), Double.NaN, context)));
				break;
			case "Extension":
				break;
			default:
				throw new PmmlException("Unsupported element " + localName(predictor) + " in " + context);
			}
		}
		return new Table(intercept, predictors.toArray(Predictor[]::new));
	}

	private static double number(String text, double defaultValue, String context) {
		if (text == null || text.isEmpty()) {
			if (Double.isNaN(defaultValue)) {
				throw new PmmlException("Missing number in " + context);
			}
			return defaultValue;
		}
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException e) {
			throw new PmmlException("Not a number: '" + text + "' in " + context);
		}
	}

	private void addSlot(String name, List<String> slotNames) {
		if (name.isEmpty()) {
			throw new PmmlException("Field without name");
		}
		if (slots.putIfAbsent(name, slotNames.size()) != null) {
			throw new PmmlException("Field '" + name + "' is defined twice");
		}
		slotNames.add(name);
	}

	private void checkForCycles() {
		int[] state = new int[names.length]; // 0 new, 1 on stack, 2 done
		for (int slot = numberOfInputs; slot < names.length; slot++) {
			visit(slot, state);
		}
	}

	private void visit(int slot, int[] state) {
		if (state[slot] == 2) {
			return;
		}
		if (state[slot] == 1) {
			throw new PmmlException("Cyclic definition involving field '" + names[slot] + "'");
		}
		state[slot] = 1;
		for (int dependency : dependencies[slot]) {
			visit(dependency, state);
		}
		state[slot] = 2;
	}

	private Set<String> inputsReachableFrom(BitSet roots) {
		BitSet seen = new BitSet();
		List<Integer> stack = new ArrayList<>(roots.stream().boxed().toList());
		while (!stack.isEmpty()) {
			int slot = stack.remove(stack.size() - 1);
			if (seen.get(slot)) {
				continue;
			}
			seen.set(slot);
			for (int dependency : dependencies[slot]) {
				stack.add(dependency);
			}
		}
		Set<String> inputs = new LinkedHashSet<>();
		seen.stream().filter(s -> s < numberOfInputs).forEach(s -> inputs.add(names[s]));
		return inputs;
	}

	private int index(String alternative) {
		Integer index = alternativeIndex.get(alternative);
		if (index == null) {
			throw new PmmlException("Unknown alternative '" + alternative + "'; the model has " + alternatives);
		}
		return index;
	}

	// --- DOM helpers ---

	private static Element findModel(Element pmml) {
		Element model = null;
		for (Element element : childElements(pmml)) {
			String name = localName(element);
			if (MODEL_ELEMENTS.contains(name)) {
				if (!name.equals("RegressionModel")) {
					throw new PmmlException("Unsupported model type " + name
							+ ": only the choice-model profile (RegressionModel, as written by apollo2pmml) is supported");
				}
				if (model != null) {
					throw new PmmlException("More than one model in the PMML file");
				}
				model = element;
			}
		}
		if (model == null) {
			throw new PmmlException("No model found in the PMML file");
		}
		return model;
	}

	private static String targetField(Element model) {
		for (Element field : children(requiredChild(model, "MiningSchema"), "MiningField")) {
			String usage = field.getAttribute("usageType");
			if (usage.equals("target") || usage.equals("predicted")) {
				return field.getAttribute("name");
			}
		}
		return null;
	}

	private static Normalization normalization(Element model) {
		String method = model.getAttribute("normalizationMethod");
		if (method.isEmpty()) {
			return Normalization.none;
		}
		try {
			return Normalization.valueOf(method);
		} catch (IllegalArgumentException e) {
			throw new PmmlException("Unsupported normalizationMethod " + method + "; supported are none, simplemax, softmax");
		}
	}

	private static boolean isParameter(Element derivedField) {
		return children(derivedField, "Extension").stream().anyMatch(e -> PARAMETER_EXTENSION.equals(e.getAttribute("name")));
	}

	private static Optional<String> extension(Element element, String name) {
		return children(element, "Extension").stream().filter(e -> name.equals(e.getAttribute("name")))
				.map(e -> e.getAttribute("value")).filter(v -> !v.isEmpty()).findFirst();
	}

	/** the expression of a DerivedField or OutputField: its first child that is not an Extension or Value */
	private static Element expressionElement(Element field) {
		for (Element element : childElements(field)) {
			String name = localName(element);
			if (!name.equals("Extension") && !name.equals("Value")) {
				return element;
			}
		}
		return null;
	}

	private static String localName(Node node) {
		return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
	}

	private static List<Element> childElements(Element parent) {
		List<Element> elements = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element) {
				elements.add(element);
			}
		}
		return elements;
	}

	private static List<Element> children(Element parent, String name) {
		return childElements(parent).stream().filter(e -> name.equals(localName(e))).toList();
	}

	private static Element child(Element parent, String name) {
		List<Element> elements = children(parent, name);
		return elements.isEmpty() ? null : elements.get(0);
	}

	private static Element requiredChild(Element parent, String name) {
		Element element = child(parent, name);
		if (element == null) {
			throw new PmmlException("Missing element " + name + " in " + localName(parent));
		}
		return element;
	}
}
