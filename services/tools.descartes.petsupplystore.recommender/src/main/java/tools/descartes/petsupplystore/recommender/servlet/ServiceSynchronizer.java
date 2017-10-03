/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tools.descartes.petsupplystore.recommender.servlet;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.descartes.petsupplystore.entities.Order;
import tools.descartes.petsupplystore.entities.OrderItem;
import tools.descartes.petsupplystore.recommender.algorithm.RecommenderSelector;
import tools.descartes.petsupplystore.registryclient.Service;
import tools.descartes.petsupplystore.registryclient.loadbalancers.ServiceLoadBalancer;
import tools.descartes.petsupplystore.registryclient.rest.LoadBalancedCRUDOperations;

/**
 * This class organizes the communication with the other services and
 * synchronizes on startup and training.
 * 
 * @author Johannes Grohmann
 *
 */
public final class ServiceSynchronizer {

	private ServiceSynchronizer() {

	}

	private static final Logger LOG = LoggerFactory.getLogger(ServiceSynchronizer.class);

	/**
	 * The maximum considered time in milliseconds. Long.MAX_VALUE signals no entry,
	 * e.g. all orders are used for training.
	 */
	private static long maxTime = Long.MAX_VALUE;

	/**
	 * @return the maxTime
	 */
	public static long getMaxTime() {
		return ServiceSynchronizer.maxTime;
	}

	/**
	 * @param maxTime
	 *            the maxTime to set
	 */
	public static void setMaxTime(String maxTime) {
		ServiceSynchronizer.maxTime = toMillis(maxTime);
	}

	/**
	 * @param maxTime
	 *            the maxTime to set
	 */
	public static void setMaxTime(long maxTime) {
		ServiceSynchronizer.maxTime = maxTime;
	}

	/**
	 * Connects via REST to the database and retrieves all {@link OrderItem}s and
	 * all {@link Order}s. Then, it triggers the training of the recommender.
	 * 
	 * @return The number of elements retrieved from the database or -1 if the
	 *         process failed.
	 */
	public static long retrieveDataAndRetrain() {
		LOG.trace("Retrieving data objects from database...");
		List<OrderItem> items = null;
		List<Order> orders = null;
		// retrieve
		try {
			items = LoadBalancedCRUDOperations.getEntities(Service.PERSISTENCE, "orderitems", OrderItem.class, -1, -1);
			long noItems = items.size();
			LOG.trace("Retrieved " + noItems + " orderItems, starting retrieving of orders now.");
			orders = LoadBalancedCRUDOperations.getEntities(Service.PERSISTENCE, "orders", Order.class, -1, -1);
			long noOrders = orders.size();
			LOG.trace("Retrieved " + noOrders + " orders, starting training now.");
		} catch (Exception e) {
			LOG.error("Database retrieving failed.");
			return -1;
		}
		// filter lists
		filterLists(items, orders);
		// train instance
		RecommenderSelector.getInstance().train(items, orders);
		LOG.trace("Finished training, ready for recommendation.");
		return items.size() + orders.size();
	}

	private static void filterLists(List<OrderItem> orderItems, List<Order> orders) {
		// since we are not registered ourselves, we can multicast to all services
		List<Response> maxTimeResponses = ServiceLoadBalancer.multicastRESTOperation(Service.RECOMMENDER,
				"train/timestamp", Response.class,
				client -> client.getService().path(client.getApplicationURI()).path(client.getEndpointURI())
						.request(MediaType.TEXT_PLAIN).accept(MediaType.TEXT_PLAIN).get());
		for (Response response : maxTimeResponses) {
			if (response != null && response.getStatus() == Response.Status.OK.getStatusCode()) {
				// only consider if status was fine
				long milliTS = response.readEntity(Long.class);
				if (maxTime != Long.MAX_VALUE && maxTime != milliTS) {
					LOG.warn("Services disagree about timestamp: " + maxTime + " vs " + milliTS + ".");
				}
				maxTime = Math.min(maxTime, milliTS);
			} else {
				LOG.warn("Service " + response.toString() + " was not available for time-check.");
			}
		}
		if (maxTime == Long.MAX_VALUE) {
			// we are the only known service
			// therefore we find max and set it
			for (Order or : orders) {
				maxTime = Math.max(maxTime, toMillis(or.getTime()));
			}
		}
		filterForMaxtimeStamp(orderItems, orders);
	}

	private static void filterForMaxtimeStamp(List<OrderItem> orderItems, List<Order> orders) {
		// filter orderItems and orders and ignore junger entries.
		List<Order> remove = new ArrayList<>();
		for (Order or : orders) {
			if (toMillis(or.getTime()) > maxTime) {
				remove.add(or);
			}
		}
		orders.removeAll(remove);

		List<OrderItem> removeItems = new ArrayList<>();
		for (OrderItem orderItem : orderItems) {
			boolean contained = false;
			for (Order or : orders) {
				if (or.getId() == orderItem.getOrderId()) {
					contained = true;
				}
			}
			if (!contained) {
				removeItems.add(orderItem);
			}
		}
		orderItems.removeAll(removeItems);
	}

	private static long toMillis(String date) {
		TemporalAccessor temporalAccessor = DateTimeFormatter.ISO_LOCAL_DATE_TIME.parse(date);
		LocalDateTime localDateTime = LocalDateTime.from(temporalAccessor);
		ZonedDateTime zonedDateTime = ZonedDateTime.of(localDateTime, ZoneId.systemDefault());
		Instant instant = Instant.from(zonedDateTime);
		return instant.toEpochMilli();
	}

}
