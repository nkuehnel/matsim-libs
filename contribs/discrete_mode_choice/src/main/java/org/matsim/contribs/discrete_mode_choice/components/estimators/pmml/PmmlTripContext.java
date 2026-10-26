package org.matsim.contribs.discrete_mode_choice.components.estimators.pmml;

import java.util.List;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.pt.routes.TransitPassengerRoute;

/**
 * Everything a {@link PmmlVariableProvider} may use to compute a variable: the
 * person, the trip, the MATSim mode, the PMML alternative it is evaluated as,
 * and the routed trip.
 *
 * @author nkuehnel
 */
public final class PmmlTripContext {
	private final Person person;
	private final String mode;
	private final String alternative;
	private final DiscreteModeChoiceTrip trip;
	private final List<? extends PlanElement> routedTrip;
	private final List<TripCandidate> previousTrips;
	private RouteSummary routeSummary;

	public PmmlTripContext(Person person, String mode, String alternative, DiscreteModeChoiceTrip trip,
			List<? extends PlanElement> routedTrip, List<TripCandidate> previousTrips) {
		this.person = person;
		this.mode = mode;
		this.alternative = alternative;
		this.trip = trip;
		this.routedTrip = routedTrip;
		this.previousTrips = previousTrips;
	}

	public Person getPerson() {
		return person;
	}

	/** the MATSim mode that was routed */
	public String getMode() {
		return mode;
	}

	/** the alternative of the PMML model the mode is evaluated as */
	public String getAlternative() {
		return alternative;
	}

	public DiscreteModeChoiceTrip getTrip() {
		return trip;
	}

	public List<? extends PlanElement> getRoutedTrip() {
		return routedTrip;
	}

	public List<TripCandidate> getPreviousTrips() {
		return previousTrips;
	}

	/** aggregates of the routed trip, computed once per context */
	public RouteSummary getRouteSummary() {
		if (routeSummary == null) {
			routeSummary = RouteSummary.of(routedTrip, mode);
		}
		return routeSummary;
	}

	/**
	 * Aggregates of a routed trip. Main legs are the legs whose mode equals the
	 * routed mode (for pt: the transit legs); all other legs count as access,
	 * egress and transfer walks. Waiting times are known for transit legs
	 * (boarding time minus leg departure) and are not part of the main travel
	 * time.
	 *
	 * @param mainTravelTime_s  travel time of the main legs without waiting
	 * @param waitTime_s        waiting time before boarding (transit legs)
	 * @param accessEgressTime_s travel time of all other legs
	 * @param totalTravelTime_s travel time of all legs including waiting
	 * @param mainDistance_m    network or teleported distance of the main legs
	 * @param mainLegs          number of main legs
	 */
	public record RouteSummary(double mainTravelTime_s, double waitTime_s, double accessEgressTime_s,
			double totalTravelTime_s, double mainDistance_m, int mainLegs) {

		public static RouteSummary of(List<? extends PlanElement> routedTrip, String mode) {
			double main = 0.0, wait = 0.0, other = 0.0, distance = 0.0;
			int legs = 0;
			for (PlanElement element : routedTrip) {
				if (!(element instanceof Leg leg)) {
					continue;
				}
				double travelTime = travelTime(leg);
				boolean isMain = leg.getMode().equals(mode)
						|| (mode.equals(TransportMode.pt) && leg.getRoute() instanceof TransitPassengerRoute);
				if (!isMain) {
					other += travelTime;
					continue;
				}
				legs++;
				double legWait = 0.0;
				if (leg.getRoute() instanceof TransitPassengerRoute route && route.getBoardingTime().isDefined()
						&& leg.getDepartureTime().isDefined()) {
					legWait = Math.max(0.0, route.getBoardingTime().seconds() - leg.getDepartureTime().seconds());
				}
				wait += legWait;
				main += Math.max(0.0, travelTime - legWait);
				if (leg.getRoute() != null && Double.isFinite(leg.getRoute().getDistance())) {
					distance += leg.getRoute().getDistance();
				}
			}
			return new RouteSummary(main, wait, other, main + wait + other, distance, legs);
		}

		private static double travelTime(Leg leg) {
			if (leg.getTravelTime().isDefined()) {
				return leg.getTravelTime().seconds();
			}
			if (leg.getRoute() != null && leg.getRoute().getTravelTime().isDefined()) {
				return leg.getRoute().getTravelTime().seconds();
			}
			throw new IllegalStateException("Leg of mode " + leg.getMode() + " has no travel time");
		}
	}
}
