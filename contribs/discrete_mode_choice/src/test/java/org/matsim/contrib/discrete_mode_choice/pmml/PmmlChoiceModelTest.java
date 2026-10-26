package org.matsim.contrib.discrete_mode_choice.pmml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlChoiceModel;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlException;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlVariable;

public class PmmlChoiceModelTest {
	private static final double EPS = 1e-12;

	/**
	 * Two-alternative MNL in the layout of apollo2pmml: parameters, a helper
	 * variable, utilities, exp of available alternatives, probabilities.
	 */
	public static String mnl(String carUtility) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<PMML xmlns="http://www.dmg.org/PMML-4_4" version="4.4">
				  <Header description="test model"/>
				  <DataDictionary>
				    <DataField name="car.tt" optype="continuous" dataType="double"><Extension name="unit" value="min"/><Extension name="description" value="car time"/></DataField>
				    <DataField name="pt.tt" optype="continuous" dataType="double"><Extension name="unit" value="min"/></DataField>
				    <DataField name="trip.distance_km" optype="continuous" dataType="double"><Extension name="unit" value="km"/></DataField>
				    <DataField name="car.av" optype="continuous" dataType="double"/>
				    <DataField name="choice" optype="categorical" dataType="string"><Value value="car"/><Value value="pt"/></DataField>
				  </DataDictionary>
				  <TransformationDictionary>
				    <DerivedField name="b_tt" optype="continuous" dataType="double"><Extension name="parameter" value="estimate"/><Constant dataType="double">-0.05</Constant></DerivedField>
				    <DerivedField name="asc_pt" optype="continuous" dataType="double"><Extension name="parameter" value="estimate"/><Constant dataType="double">-0.5</Constant></DerivedField>
				    <DerivedField name="lambda" optype="continuous" dataType="double"><Extension name="parameter" value="estimate"/><Constant dataType="double">0.3</Constant></DerivedField>
				    <DerivedField name="d" optype="continuous" dataType="double">
				      <Apply function="pow"><Apply function="/"><Apply function="max"><FieldRef field="trip.distance_km"/><Constant dataType="double">1</Constant></Apply><Constant dataType="double">20</Constant></Apply><FieldRef field="lambda"/></Apply>
				    </DerivedField>
				    <DerivedField name="U(car)" optype="continuous" dataType="double">%s</DerivedField>
				    <DerivedField name="U(pt)" optype="continuous" dataType="double">
				      <Apply function="+"><FieldRef field="asc_pt"/><Apply function="*"><Apply function="*"><FieldRef field="b_tt"/><FieldRef field="d"/></Apply><FieldRef field="pt.tt"/></Apply></Apply>
				    </DerivedField>
				    <DerivedField name="E(car)" optype="continuous" dataType="double">
				      <Apply function="if"><Apply function="notEqual"><FieldRef field="car.av"/><Constant dataType="double">0</Constant></Apply><Apply function="exp"><FieldRef field="U(car)"/></Apply><Constant dataType="double">0</Constant></Apply>
				    </DerivedField>
				    <DerivedField name="E(pt)" optype="continuous" dataType="double"><Apply function="exp"><FieldRef field="U(pt)"/></Apply></DerivedField>
				    <DerivedField name="E(sum)" optype="continuous" dataType="double"><Apply function="sum"><FieldRef field="E(car)"/><FieldRef field="E(pt)"/></Apply></DerivedField>
				    <DerivedField name="P(car)" optype="continuous" dataType="double"><Apply function="/"><FieldRef field="E(car)"/><FieldRef field="E(sum)"/></Apply></DerivedField>
				    <DerivedField name="P(pt)" optype="continuous" dataType="double"><Apply function="/"><FieldRef field="E(pt)"/><FieldRef field="E(sum)"/></Apply></DerivedField>
				    <DerivedField name="label" optype="categorical" dataType="string"><Constant dataType="string">x</Constant></DerivedField>
				  </TransformationDictionary>
				  <RegressionModel modelName="test" functionName="classification" normalizationMethod="simplemax">
				    <MiningSchema>
				      <MiningField name="car.tt"/><MiningField name="pt.tt"/><MiningField name="trip.distance_km"/><MiningField name="car.av"/>
				      <MiningField name="choice" usageType="target"/>
				    </MiningSchema>
				    <Output>
				      <OutputField name="probability(car)" optype="continuous" dataType="double" feature="probability" value="car"/>
				      <OutputField name="probability(pt)" optype="continuous" dataType="double" feature="probability" value="pt"/>
				      <OutputField name="utility(car)" optype="continuous" dataType="double" feature="transformedValue"><FieldRef field="U(car)"/></OutputField>
				      <OutputField name="utility(pt)" optype="continuous" dataType="double" feature="transformedValue"><FieldRef field="U(pt)"/></OutputField>
				    </Output>
				    <RegressionTable targetCategory="car" intercept="0"><NumericPredictor name="P(car)" coefficient="1"/></RegressionTable>
				    <RegressionTable targetCategory="pt" intercept="0"><NumericPredictor name="P(pt)" coefficient="1"/></RegressionTable>
				  </RegressionModel>
				</PMML>
				""".formatted(carUtility);
	}

	public static final String CAR_UTILITY = """
			<Apply function="*"><Apply function="*"><FieldRef field="b_tt"/><FieldRef field="d"/></Apply><FieldRef field="car.tt"/></Apply>""";

	public static PmmlChoiceModel read(String pmml) {
		return PmmlChoiceModel.read(new ByteArrayInputStream(pmml.getBytes(StandardCharsets.UTF_8)));
	}

	private static double d(double km) {
		return Math.pow(Math.max(km, 1.0) / 20.0, 0.3);
	}

	@Test
	void testContractAndParameters() {
		PmmlChoiceModel model = read(mnl(CAR_UTILITY));

		assertEquals("test model", model.getDescription());
		assertEquals(List.of("car", "pt"), model.getAlternatives());
		assertEquals(List.of("car.tt", "pt.tt", "trip.distance_km", "car.av"),
				model.getVariables().stream().map(PmmlVariable::name).toList());
		assertEquals(Optional.of("min"), model.getVariable("car.tt").get().unit());
		assertEquals(Optional.of("car time"), model.getVariable("car.tt").get().description());
		assertEquals(Optional.empty(), model.getVariable("car.av").get().unit());
		assertEquals(Map.of("b_tt", -0.05, "asc_pt", -0.5, "lambda", 0.3), model.getParameters());

		// utilities only depend on the inputs they refer to, availabilities only enter the probabilities
		assertEquals(Set.of("car.tt", "trip.distance_km"), model.getUtilityInputs("car"));
		assertEquals(Set.of("pt.tt", "trip.distance_km"), model.getUtilityInputs("pt"));
		assertEquals(Set.of("car.tt", "pt.tt", "trip.distance_km", "car.av"), model.getProbabilityInputs());
		assertTrue(model.hasUtilities());
	}

	@Test
	void testUtilitiesAndProbabilities() {
		PmmlChoiceModel model = read(mnl(CAR_UTILITY));
		PmmlChoiceModel.Inputs inputs = model.newInputs().set("car.tt", 20.0).set("pt.tt", 35.0)
				.set("trip.distance_km", 12.0).set("car.av", 1.0);

		double uCar = -0.05 * d(12.0) * 20.0;
		double uPt = -0.5 - 0.05 * d(12.0) * 35.0;
		assertEquals(uCar, model.getUtility("car", inputs), EPS);
		assertEquals(uPt, model.getUtility("pt", inputs), EPS);

		double[] p = model.getProbabilities(inputs);
		double pCar = Math.exp(uCar) / (Math.exp(uCar) + Math.exp(uPt));
		assertEquals(pCar, p[0], EPS);
		assertEquals(1.0 - pCar, p[1], EPS);

		// changed inputs are picked up (derived fields are recomputed)
		inputs.set("car.av", 0.0);
		p = model.getProbabilities(inputs);
		assertEquals(0.0, p[0], EPS);
		assertEquals(1.0, p[1], EPS);
		assertEquals(d(0.5), model.evaluate("d", inputs.set("trip.distance_km", 0.5)), EPS);
	}

	@Test
	void testUtilityNeedsOnlyItsOwnInputs() {
		PmmlChoiceModel model = read(mnl(CAR_UTILITY));
		PmmlChoiceModel.Inputs inputs = model.newInputs().set("car.tt", 10.0).set("trip.distance_km", 5.0);
		assertEquals(-0.05 * d(5.0) * 10.0, model.getUtility("car", inputs), EPS);

		// a missing input is an error, never a silent zero
		PmmlException e = assertThrows(PmmlException.class, () -> model.getUtility("pt", inputs));
		assertTrue(e.getMessage().contains("pt.tt"), e.getMessage());
		assertThrows(PmmlException.class, () -> inputs.set("unknown", 1.0));
		assertThrows(PmmlException.class, () -> inputs.set("d", 1.0)); // derived, not an input
	}

	@Test
	void testParameterOverride() {
		PmmlChoiceModel model = read(mnl(CAR_UTILITY));
		PmmlChoiceModel calibrated = model.withParameters(Map.of("asc_pt", 0.25));
		PmmlChoiceModel.Inputs inputs = calibrated.newInputs().set("pt.tt", 30.0).set("trip.distance_km", 20.0);

		assertEquals(0.25 - 0.05 * 30.0, calibrated.getUtility("pt", inputs), EPS);
		assertEquals(0.25, calibrated.getParameters().get("asc_pt"));
		assertEquals(-0.5, model.getParameters().get("asc_pt")); // the original is unchanged

		// only parameters can be overridden
		assertThrows(PmmlException.class, () -> model.withParameters(Map.of("d", 1.0)));
		assertThrows(PmmlException.class, () -> model.withParameters(Map.of("nope", 1.0)));
		// inputs belong to their model
		assertThrows(PmmlException.class, () -> model.getUtility("pt", inputs));
	}

	@Test
	void testFunctions() {
		String utility = """
				<Apply function="sum">
				  <Apply function="if"><Apply function="and"><Apply function="greaterThan"><FieldRef field="car.tt"/><Constant>10</Constant></Apply>
				    <Apply function="not"><Apply function="equal"><FieldRef field="car.tt"/><Constant>99</Constant></Apply></Apply></Apply>
				    <Apply function="ln"><FieldRef field="car.tt"/></Apply>
				    <Apply function="sqrt"><FieldRef field="car.tt"/></Apply></Apply>
				  <Apply function="min"><FieldRef field="car.tt"/><Constant>3</Constant><Constant>7</Constant></Apply>
				  <Apply function="abs"><Apply function="-"><Constant>2</Constant><FieldRef field="car.tt"/></Apply></Apply>
				  <Apply function="avg"><Constant>1</Constant><Constant>2</Constant></Apply>
				  <Apply function="if"><Apply function="or"><Apply function="lessOrEqual"><FieldRef field="car.tt"/><Constant>0</Constant></Apply><Constant dataType="boolean">true</Constant></Apply>
				    <Apply function="log10"><Constant>100</Constant></Apply><FieldRef field="missing"/></Apply>
				  <Apply function="threshold"><FieldRef field="car.tt"/><Constant>5</Constant></Apply>
				  <Apply function="floor"><Constant>1.7</Constant></Apply>
				  <Apply function="ceil"><Constant>1.2</Constant></Apply>
				  <Apply function="round"><Constant>2.5</Constant></Apply>
				  <Apply function="product"><Constant>2</Constant><Constant>3</Constant><Constant>0.5</Constant></Apply>
				</Apply>""";
		// 'missing' does not exist: unknown fields are rejected when reading
		assertThrows(PmmlException.class, () -> read(mnl(utility)));

		PmmlChoiceModel model = read(mnl(utility.replace("<FieldRef field=\"missing\"/>", "<FieldRef field=\"pt.tt\"/>")));
		for (double tt : new double[] { 4.0, 16.0 }) {
			PmmlChoiceModel.Inputs inputs = model.newInputs().set("car.tt", tt);
			double expected = (tt > 10 ? Math.log(tt) : Math.sqrt(tt)) + Math.min(tt, 3) + Math.abs(2 - tt) + 1.5 + 2.0
					+ (tt > 5 ? 1 : 0) + 1 + 2 + 3 + 3;
			// pt.tt is not set: the else branch of the second if must not be evaluated
			assertEquals(expected, model.getUtility("car", inputs), EPS);
			assertEquals(Set.of("car.tt", "pt.tt"), model.getUtilityInputs("car"));
		}
	}

	@Test
	void testStringFieldsAreOnlyRejectedWhenEvaluated() {
		PmmlChoiceModel model = read(mnl(CAR_UTILITY));
		assertThrows(PmmlException.class, () -> model.evaluate("label", model.newInputs()));
	}

	@Test
	void testSoftmaxRegressionWithoutUtilityOutputs() {
		String pmml = """
				<PMML xmlns="http://www.dmg.org/PMML-4_4" version="4.4">
				  <DataDictionary>
				    <DataField name="x" optype="continuous" dataType="double"/>
				    <DataField name="y" optype="continuous" dataType="double"/>
				    <DataField name="choice" optype="categorical" dataType="string"/>
				  </DataDictionary>
				  <RegressionModel functionName="classification" normalizationMethod="softmax">
				    <MiningSchema><MiningField name="x"/><MiningField name="y"/><MiningField name="choice" usageType="target"/></MiningSchema>
				    <RegressionTable targetCategory="a" intercept="0.5"><NumericPredictor name="x" exponent="2" coefficient="-0.1"/></RegressionTable>
				    <RegressionTable targetCategory="b" intercept="0"><PredictorTerm coefficient="0.2"><FieldRef field="x"/><FieldRef field="y"/></PredictorTerm></RegressionTable>
				  </RegressionModel>
				</PMML>""";
		PmmlChoiceModel model = read(pmml);
		PmmlChoiceModel.Inputs inputs = model.newInputs().set("x", 3.0).set("y", 2.0);
		double ua = 0.5 - 0.1 * 9.0;
		double ub = 0.2 * 6.0;
		assertEquals(ua, model.getUtility("a", inputs), EPS);
		assertEquals(ub, model.getUtility("b", inputs), EPS);
		assertEquals(Set.of("x"), model.getUtilityInputs("a"));
		assertEquals(Math.exp(ua) / (Math.exp(ua) + Math.exp(ub)), model.getProbabilities(inputs)[0], EPS);
	}

	@Test
	void testUnsupportedContentIsRejected() {
		// other model types
		assertThrows(PmmlException.class, () -> read("""
				<PMML xmlns="http://www.dmg.org/PMML-4_4" version="4.4"><DataDictionary/>
				<MiningModel functionName="classification"><MiningSchema/></MiningModel></PMML>"""));
		// unknown functions
		PmmlException e = assertThrows(PmmlException.class,
				() -> read(mnl("<Apply function=\"sin\"><FieldRef field=\"car.tt\"/></Apply>")));
		assertTrue(e.getMessage().contains("sin"), e.getMessage());
		// wrong arity
		assertThrows(PmmlException.class, () -> read(mnl("<Apply function=\"pow\"><FieldRef field=\"car.tt\"/></Apply>")));
		// other expression types
		assertThrows(PmmlException.class, () -> read(mnl("<NormContinuous field=\"car.tt\"/>")));
		// cycles
		assertThrows(PmmlException.class, () -> read(mnl("<FieldRef field=\"E(car)\"/>")));
		// no DOCTYPE (external entities)
		assertThrows(PmmlException.class, () -> read("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><PMML/>"));
	}

	@Test
	void testNoUtilityOutputs() {
		String pmml = mnl(CAR_UTILITY).replaceAll("<OutputField name=\"utility\\((car|pt)\\)\"[^\\n]*</OutputField>", "");
		PmmlChoiceModel model = read(pmml);
		assertFalse(model.hasUtilities());
		assertThrows(PmmlException.class, () -> model.getUtility("car", model.newInputs()));
	}
}
