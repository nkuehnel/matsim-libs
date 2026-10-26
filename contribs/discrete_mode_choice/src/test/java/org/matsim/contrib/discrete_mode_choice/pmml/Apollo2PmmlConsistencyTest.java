package org.matsim.contrib.discrete_mode_choice.pmml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlChoiceModel;

/**
 * Consistency of the built-in evaluator with PMML4S, an independent PMML
 * evaluator (Apache-2.0, test dependency only), on the end-to-end check model
 * of apollo2pmml: an Apollo MNL with distance elasticities, income- and
 * distance-dependent cost, Box-Cox transformations, kinks via pmax, helper
 * functions and availabilities, 400 random rows. apollo2pmml's own check shows
 * that PMML4S reproduces Apollo's probabilities on these rows (about 4e-15).
 */
public class Apollo2PmmlConsistencyTest {
	private static final String PMML = "/pmml/apollo2pmml-check.pmml";
	private static final String INPUT = "/pmml/apollo2pmml-check-input.csv";
	private static final String PMML4S_OUTPUT = "/pmml/apollo2pmml-check-pmml4s.csv";
	private static final double TOLERANCE = 1e-12;

	private record Csv(List<String> header, List<double[]> rows, List<String[]> raw) {
	}

	private static Csv csv(String resource) throws IOException {
		try (InputStream stream = Apollo2PmmlConsistencyTest.class.getResourceAsStream(resource);
				BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			List<String> header = List.of(reader.readLine().replace("\"", "").split(","));
			List<double[]> rows = new ArrayList<>();
			List<String[]> raw = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				String[] cells = line.split(",");
				raw.add(cells);
				double[] values = new double[cells.length];
				for (int i = 0; i < cells.length; i++) {
					try {
						values[i] = Double.parseDouble(cells[i]);
					} catch (NumberFormatException e) {
						values[i] = Double.NaN; // e.g. the drawn choice
					}
				}
				rows.add(values);
			}
			return new Csv(header, rows, raw);
		}
	}

	private static PmmlChoiceModel model() {
		return PmmlChoiceModel.read(Apollo2PmmlConsistencyTest.class.getResource(PMML));
	}

	private static PmmlChoiceModel.Inputs inputs(PmmlChoiceModel model, Csv input, int row) {
		PmmlChoiceModel.Inputs inputs = model.newInputs();
		for (int i = 0; i < input.header().size(); i++) {
			inputs.set(input.header().get(i), input.rows().get(row)[i]);
		}
		return inputs;
	}

	@Test
	void testVariableContract() {
		PmmlChoiceModel model = model();
		assertEquals(List.of("car", "pt", "bike", "walk"), model.getAlternatives());
		assertEquals("km", model.getVariable("distance_km").get().unit().get());
		assertEquals("min", model.getVariable("tt_car").get().unit().get());
		assertEquals(Set.of("distance_km", "tt_bike"), model.getUtilityInputs("bike"));
		assertEquals(Set.of("distance_km", "income_eq", "tt_car", "cost_car", "park_car"), model.getUtilityInputs("car"));
	}

	/** against the outputs PMML4S produced in the apollo2pmml check (stored with the test) */
	@Test
	void testAgainstStoredPmml4sOutput() throws IOException {
		PmmlChoiceModel model = model();
		Csv input = csv(INPUT);
		Csv output = csv(PMML4S_OUTPUT);
		List<String> alternatives = model.getAlternatives();

		double maxProbability = 0.0;
		double maxUtility = 0.0;
		for (int row = 0; row < input.rows().size(); row++) {
			PmmlChoiceModel.Inputs inputs = inputs(model, input, row);
			double[] p = model.getProbabilities(inputs);
			double[] u = model.getUtilities(inputs);
			for (int k = 0; k < alternatives.size(); k++) {
				String alternative = alternatives.get(k);
				double expectedP = output.rows().get(row)[output.header().indexOf("probability(" + alternative + ")")];
				double expectedU = output.rows().get(row)[output.header().indexOf("utility(" + alternative + ")")];
				maxProbability = Math.max(maxProbability, Math.abs(p[k] - expectedP));
				maxUtility = Math.max(maxUtility, Math.abs(u[k] - expectedU));
			}
		}
		assertTrue(maxProbability < TOLERANCE, "max |P - P_pmml4s| = " + maxProbability);
		assertTrue(maxUtility < TOLERANCE, "max |U - U_pmml4s| = " + maxUtility);
	}

	/** against PMML4S itself, run on the same file and rows */
	@Test
	void testAgainstPmml4s() throws IOException {
		URL url = Apollo2PmmlConsistencyTest.class.getResource(PMML);
		org.pmml4s.model.Model reference = org.pmml4s.model.Model.fromFile(url.getPath());
		PmmlChoiceModel model = model();
		Csv input = csv(INPUT);

		double max = 0.0;
		for (int row = 0; row < input.rows().size(); row++) {
			Map<String, Object> values = new HashMap<>();
			for (int i = 0; i < input.header().size(); i++) {
				values.put(input.header().get(i), input.rows().get(row)[i]);
			}
			Map<String, Object> expected = reference.predict(values);

			PmmlChoiceModel.Inputs inputs = inputs(model, input, row);
			double[] p = model.getProbabilities(inputs);
			double[] u = model.getUtilities(inputs);
			for (int k = 0; k < p.length; k++) {
				String alternative = model.getAlternatives().get(k);
				max = Math.max(max, Math.abs(p[k] - ((Number) expected.get("probability(" + alternative + ")")).doubleValue()));
				max = Math.max(max, Math.abs(u[k] - ((Number) expected.get("utility(" + alternative + ")")).doubleValue()));
			}
		}
		assertTrue(max < TOLERANCE, "max difference to PMML4S = " + max);
	}
}
