package org.matsim.contribs.discrete_mode_choice.components.estimators.pmml;

import java.util.Map;
import java.util.Optional;

import org.matsim.api.core.v01.Coord;
import org.matsim.contribs.discrete_mode_choice.components.estimators.pmml.PmmlTripContext.RouteSummary;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Built-in variables, named after the variable contract
 * {@code person.*}, {@code trip.*} and {@code <alternative>.*}:
 *
 * <table>
 * <tr><th>Variable</th><th>Unit</th><th>Value</th></tr>
 * <tr><td>{@code person.<name>}</td><td></td><td>person attribute {@code <name>} (number or boolean)</td></tr>
 * <tr><td>{@code trip.beeline_km}</td><td>km</td><td>beeline distance between origin and destination activity</td></tr>
 * <tr><td>{@code trip.departure_h}</td><td>h</td><td>departure time of the trip</td></tr>
 * <tr><td>{@code trip.<name>}</td><td></td><td>trip attribute {@code <name>} (number or boolean)</td></tr>
 * <tr><td>{@code <alt>.tt}</td><td>min</td><td>travel time of the main legs (in-vehicle time for pt)</td></tr>
 * <tr><td>{@code <alt>.wait}</td><td>min</td><td>waiting time before boarding (pt)</td></tr>
 * <tr><td>{@code <alt>.access}</td><td>min</td><td>access, egress and transfer walks together</td></tr>
 * <tr><td>{@code <alt>.total_tt}</td><td>min</td><td>door-to-door travel time</td></tr>
 * <tr><td>{@code <alt>.distance_km}</td><td>km</td><td>distance of the main legs</td></tr>
 * <tr><td>{@code <alt>.transfers}</td><td></td><td>number of main legs minus one</td></tr>
 * <tr><td>{@code <alt>.av}</td><td></td><td>1: the alternative is offered (availability is decided by DMC)</td></tr>
 * <tr><td>{@code <alt>.hyp}</td><td></td><td>0: a simulated connection is never hypothetical</td></tr>
 * </table>
 *
 * {@code <alt>} is the alternative being evaluated. A missing person or trip
 * attribute is an error, never a silent zero. Costs are not built in: they
 * depend on the scenario and come from a custom {@link PmmlVariableProvider}.
 *
 * @author nkuehnel
 */
public class DefaultPmmlVariableProvider implements PmmlVariableProvider {
	public static final String PERSON = "person.";
	public static final String TRIP = "trip.";

	private static final Map<String, String> UNITS = Map.of( //
			"tt", "min", "wait", "min", "access", "min", "total_tt", "min", "distance_km", "km");

	@Override
	public boolean provides(String variable, String alternative) {
		if (variable.startsWith(PERSON) || variable.startsWith(TRIP)) {
			return variable.indexOf('.') < variable.length() - 1;
		}
		String prefix = alternative + ".";
		if (!variable.startsWith(prefix)) {
			return false;
		}
		String attribute = variable.substring(prefix.length());
		return UNITS.containsKey(attribute) || attribute.equals("transfers") || attribute.equals("av")
				|| attribute.equals("hyp");
	}

	@Override
	public Optional<String> getUnit(String variable) {
		if (variable.equals("trip.beeline_km")) {
			return Optional.of("km");
		}
		if (variable.equals("trip.departure_h")) {
			return Optional.of("h");
		}
		if (variable.startsWith(PERSON) || variable.startsWith(TRIP)) {
			return Optional.empty();
		}
		return Optional.ofNullable(UNITS.get(variable.substring(variable.indexOf('.') + 1)));
	}

	@Override
	public double getValue(String variable, PmmlTripContext context) {
		if (variable.startsWith(PERSON)) {
			String name = variable.substring(PERSON.length());
			return number(context.getPerson().getAttributes().getAttribute(name), variable,
					"person " + context.getPerson().getId());
		}
		if (variable.startsWith(TRIP)) {
			String name = variable.substring(TRIP.length());
			switch (name) {
			case "beeline_km": {
				Coord origin = context.getTrip().getOriginActivity().getCoord();
				Coord destination = context.getTrip().getDestinationActivity().getCoord();
				if (origin == null || destination == null) {
					throw new IllegalStateException("trip.beeline_km needs coordinates of the activities");
				}
				return CoordUtils.calcEuclideanDistance(origin, destination) / 1000.0;
			}
			case "departure_h":
				return context.getTrip().getDepartureTime() / 3600.0;
			default:
				return number(context.getTrip().getTripAttributes().getAttribute(name), variable,
						"trip " + context.getTrip().getIndex() + " of person " + context.getPerson().getId());
			}
		}
		RouteSummary route = context.getRouteSummary();
		String attribute = variable.substring(variable.indexOf('.') + 1);
		switch (attribute) {
		case "tt":
			return route.mainTravelTime_s() / 60.0;
		case "wait":
			return route.waitTime_s() / 60.0;
		case "access":
			return route.accessEgressTime_s() / 60.0;
		case "total_tt":
			return route.totalTravelTime_s() / 60.0;
		case "distance_km":
			return route.mainDistance_m() / 1000.0;
		case "transfers":
			return Math.max(0, route.mainLegs() - 1);
		case "av":
			return 1.0;
		case "hyp":
			return 0.0;
		default:
			throw new IllegalStateException("Unknown variable " + variable);
		}
	}

	private static double number(Object value, String variable, String owner) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof Boolean bool) {
			return bool ? 1.0 : 0.0;
		}
		if (value == null) {
			throw new IllegalStateException("Variable " + variable + " is missing for " + owner);
		}
		try {
			return Double.parseDouble(value.toString());
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Variable " + variable + " of " + owner + " is not a number: " + value);
		}
	}
}
