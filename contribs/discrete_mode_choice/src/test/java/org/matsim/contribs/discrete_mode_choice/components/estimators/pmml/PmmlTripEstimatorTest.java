package org.matsim.contribs.discrete_mode_choice.components.estimators.pmml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.discrete_mode_choice.pmml.PmmlChoiceModelTest;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlChoiceModel;
import org.matsim.contribs.discrete_mode_choice.pmml.PmmlException;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.GenericRouteImpl;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

public class PmmlTripEstimatorTest {
	private static final double EPS = 1e-12;
	private static final PopulationFactory FACTORY = PopulationUtils.getFactory();
	private static final Id<Link> LINK = Id.createLinkId("l");

	private static PmmlChoiceModel model() {
		return PmmlChoiceModelTest.read(PmmlChoiceModelTest.mnl(PmmlChoiceModelTest.CAR_UTILITY));
	}

	private static PmmlTripEstimator estimator(PmmlChoiceModel model, Map<String, String> modeMapping,
			Map<String, String> aliases, Collection<? extends PmmlVariableProvider> providers) {
		return new PmmlTripEstimator(null, null, null, Set.of(), model, modeMapping, aliases, providers);
	}

	private static DiscreteModeChoiceTrip trip() {
		Activity origin = FACTORY.createActivityFromCoord("home", new Coord(0.0, 0.0));
		Activity destination = FACTORY.createActivityFromCoord("work", new Coord(3000.0, 4000.0)); // 5 km
		origin.setEndTime(8 * 3600.0);
		return new DiscreteModeChoiceTrip(origin, destination, TransportMode.walk, List.of(), 0, 0, 0,
				new AttributesImpl());
	}

	private static Leg leg(String mode, double departure, double travelTime, double distance) {
		Leg leg = FACTORY.createLeg(mode);
		leg.setDepartureTime(departure);
		leg.setTravelTime(travelTime);
		GenericRouteImpl route = new GenericRouteImpl(LINK, LINK);
		route.setTravelTime(travelTime);
		route.setDistance(distance);
		leg.setRoute(route);
		return leg;
	}

	/** walk 2 min, car 18 min over 7 km, walk 1 min */
	private static List<PlanElement> carTrip() {
		return List.of(leg(TransportMode.walk, 28800, 120, 100), leg(TransportMode.car, 28920, 1080, 7000),
				leg(TransportMode.walk, 30000, 60, 50));
	}

	/** walk 5 min, wait 4 min, ride 21 min, walk 3 min */
	private static List<PlanElement> ptTrip() {
		Leg pt = FACTORY.createLeg(TransportMode.pt);
		pt.setDepartureTime(29100);
		pt.setTravelTime(25 * 60);
		DefaultTransitPassengerRoute route = new DefaultTransitPassengerRoute(LINK, LINK, Id.create("a", org.matsim.pt.transitSchedule.api.TransitStopFacility.class),
				Id.create("b", org.matsim.pt.transitSchedule.api.TransitStopFacility.class),
				Id.create("line", org.matsim.pt.transitSchedule.api.TransitLine.class),
				Id.create("route", org.matsim.pt.transitSchedule.api.TransitRoute.class));
		route.setBoardingTime(29100 + 4 * 60);
		route.setTravelTime(25 * 60);
		route.setDistance(6000);
		pt.setRoute(route);
		return List.of(leg(TransportMode.walk, 28800, 300, 300), pt, leg(TransportMode.walk, 30600, 180, 150));
	}

	private static Person person() {
		return FACTORY.createPerson(Id.createPersonId("p"));
	}

	private static double d(double km) {
		return Math.pow(Math.max(km, 1.0) / 20.0, 0.3);
	}

	@Test
	void testDefaultVariables() {
		PmmlTripEstimator estimator = estimator(model(), Map.of(), Map.of("trip.distance_km", "trip.beeline_km"),
				List.of());
		assertEquals(Set.of("car", "pt"), Set.copyOf(estimator.getModes()));

		double car = estimator.estimateTrip(person(), TransportMode.car, trip(), List.of(), carTrip());
		assertEquals(-0.05 * d(5.0) * 18.0, car, EPS);

		// pt.tt is the in-vehicle time: leg travel time minus waiting until boarding
		double pt = estimator.estimateTrip(person(), TransportMode.pt, trip(), List.of(), ptTrip());
		assertEquals(-0.5 - 0.05 * d(5.0) * 21.0, pt, EPS);
	}

	@Test
	void testRouteSummary() {
		PmmlTripContext.RouteSummary pt = PmmlTripContext.RouteSummary.of(ptTrip(), TransportMode.pt);
		assertEquals(21 * 60, pt.mainTravelTime_s(), EPS);
		assertEquals(4 * 60, pt.waitTime_s(), EPS);
		assertEquals(8 * 60, pt.accessEgressTime_s(), EPS);
		assertEquals(33 * 60, pt.totalTravelTime_s(), EPS);
		assertEquals(6000, pt.mainDistance_m(), EPS);
		assertEquals(1, pt.mainLegs());

		PmmlTripContext.RouteSummary car = PmmlTripContext.RouteSummary.of(carTrip(), TransportMode.car);
		assertEquals(1080, car.mainTravelTime_s(), EPS);
		assertEquals(0, car.waitTime_s(), EPS);
		assertEquals(180, car.accessEgressTime_s(), EPS);
		assertEquals(7000, car.mainDistance_m(), EPS);
	}

	@Test
	void testCustomProviderAndModeMapping() {
		// drt is evaluated with the pt utility; its in-vehicle time comes from a custom provider
		PmmlVariableProvider drtTime = new PmmlVariableProvider() {
			@Override
			public boolean provides(String variable, String alternative) {
				return variable.equals("pt.tt");
			}

			@Override
			public Optional<String> getUnit(String variable) {
				return Optional.of("min");
			}

			@Override
			public double getValue(String variable, PmmlTripContext context) {
				return context.getMode().equals("drt") ? 12.0 : context.getRouteSummary().mainTravelTime_s() / 60.0;
			}
		};
		PmmlTripEstimator estimator = estimator(model(), Map.of("car", "car", "pt", "pt", "drt", "pt"),
				Map.of("trip.distance_km", "trip.beeline_km"), List.of(drtTime));

		double drt = estimator.estimateTrip(person(), "drt", trip(), List.of(), List.of(leg("drt", 28800, 900, 6000)));
		assertEquals(-0.5 - 0.05 * d(5.0) * 12.0, drt, EPS);
		double pt = estimator.estimateTrip(person(), TransportMode.pt, trip(), List.of(), ptTrip());
		assertEquals(-0.5 - 0.05 * d(5.0) * 21.0, pt, EPS);

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> estimator.estimateTrip(person(), TransportMode.bike, trip(), List.of(), List.of()));
		assertTrue(e.getMessage().contains("modeMapping"), e.getMessage());
	}

	@Test
	void testPersonAndTripAttributes() {
		PmmlTripEstimator estimator = estimator(model(), Map.of(), Map.of("trip.distance_km", "person.commute_km"),
				List.of());
		Person person = person();
		// a missing attribute is an error, never a silent zero
		assertThrows(IllegalStateException.class,
				() -> estimator.estimateTrip(person, TransportMode.car, trip(), List.of(), carTrip()));
		person.getAttributes().putAttribute("commute_km", 40.0);
		assertEquals(-0.05 * d(40.0) * 18.0,
				estimator.estimateTrip(person, TransportMode.car, trip(), List.of(), carTrip()), EPS);

		// without alias, trip.distance_km is read from the trip attributes
		PmmlTripEstimator fromTrip = estimator(model(), Map.of(), Map.of(), List.of());
		DiscreteModeChoiceTrip trip = trip();
		trip.getTripAttributes().putAttribute("distance_km", 7.5);
		assertEquals(-0.05 * d(7.5) * 18.0, fromTrip.estimateTrip(person, TransportMode.car, trip, List.of(), carTrip()), EPS);
	}

	@Test
	void testSetupErrorsAreReportedAtCreation() {
		// unit mismatch between PMML file (min) and provider (s)
		PmmlVariableProvider seconds = new PmmlVariableProvider() {
			@Override
			public boolean provides(String variable, String alternative) {
				return variable.equals("car.tt");
			}

			@Override
			public Optional<String> getUnit(String variable) {
				return Optional.of("s");
			}

			@Override
			public double getValue(String variable, PmmlTripContext context) {
				return 0.0;
			}
		};
		PmmlException e = assertThrows(PmmlException.class,
				() -> estimator(model(), Map.of(), Map.of(), List.of(seconds)));
		assertTrue(e.getMessage().contains("unit"), e.getMessage());

		// two providers for the same variable
		e = assertThrows(PmmlException.class, () -> estimator(model(), Map.of(), Map.of(), List.of(seconds, seconds)));
		assertTrue(e.getMessage().contains("more than one provider"), e.getMessage());

		// an input that nobody provides
		PmmlChoiceModel model = PmmlChoiceModelTest.read(PmmlChoiceModelTest.mnl(PmmlChoiceModelTest.CAR_UTILITY)
				.replace("<FieldRef field=\"car.tt\"/></Apply>", "<FieldRef field=\"car.av\"/></Apply>")
				.replace("name=\"car.av\"", "name=\"cost_car\"").replace("field=\"car.av\"", "field=\"cost_car\""));
		e = assertThrows(PmmlException.class, () -> estimator(model, Map.of(), Map.of(), List.of()));
		assertTrue(e.getMessage().contains("cost_car"), e.getMessage());

		// unknown alternative, unknown alias
		assertThrows(PmmlException.class, () -> estimator(model(), Map.of("car", "auto"), Map.of(), List.of()));
		assertThrows(PmmlException.class, () -> estimator(model(), Map.of(), Map.of("nope", "car.tt"), List.of()));
	}
}
