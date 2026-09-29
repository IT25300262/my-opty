package com.myopty.order.controller;

import java.util.List;
import java.util.Map;

import com.myopty.order.domain.Order;
import com.myopty.order.domain.OrderStatus;
import com.myopty.order.dto.ApiResponse;
import com.myopty.order.dto.OrderCreateRequest;
import com.myopty.order.dto.OrderResponse;
import com.myopty.order.dto.ReceiveDateRequest;
import com.myopty.order.dto.RejectionRequest;
import com.myopty.order.exception.InvalidOrderException;
import com.myopty.order.mapper.OrderMapper;
import com.myopty.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order endpoints for the Order &amp; Prescription module.
 *
 * <p>Mounted at {@code /api/orders} rather than the {@code /api/orders/progressive}
 * of the original plan, because the order type is now something the customer
 * selects and a path segment that spells it out would fix the value the URL was
 * supposed to leave open.
 *
 * <p>The Swagger {@code io.swagger...ApiResponse} annotation clashes with the
 * {@link ApiResponse} envelope, so it is referenced by its fully qualified name.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Place and retrieve customer orders for a selected lens type")
public class OrderController {

	private final OrderService service;

	public OrderController(OrderService service) {
		this.service = service;
	}

	@Operation(summary = "Place an order against a prescription",
			description = "Links the order to an existing prescription and routes it by the selected order type. "
					+ "The order is created with status PENDING_REVIEW for the shop to approve. A prescription can "
					+ "only be ordered once, and a bifocal or progressive order needs a prescription that carries a "
					+ "near addition on both eyes.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Order placed"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "Order type does not fit the prescription, or the customer does not own it"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "Prescription not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "That prescription has already been ordered") })
	@PostMapping
	public ResponseEntity<ApiResponse<OrderResponse>> create(
			@Parameter(description = "Order to place", required = true) //
			@Valid @RequestBody OrderCreateRequest request) {
		Order created = this.service.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(OrderMapper.toResponse(created)));
	}

	@Operation(summary = "View an order")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found") })
	@GetMapping("/{orderId}")
	public ApiResponse<OrderResponse> getById(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId) {
		return ApiResponse.ok(OrderMapper.toResponse(this.service.getById(orderId)));
	}

	@Operation(summary = "Search orders",
			description = "One collection endpoint for both lookups, so every filter returns the same array shape. "
					+ "At least one filter is required: an unfiltered list of every order ever placed is not a view "
					+ "anybody asks for, and it would make the 100-row cap arbitrary. Supplying both narrows the "
					+ "result to orders matching each. A prescription that has not been ordered gives an empty array "
					+ "rather than a 404, because prescription_id is unique and the answer is genuinely 'none'.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Matching orders, oldest first, at most 100"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "No filter given, or a filter value that is not a valid order status") })
	@GetMapping
	public ApiResponse<List<OrderResponse>> search(
			@Parameter(description = "Orders built from this prescription; at most one can match") //
			@RequestParam(required = false) Long prescriptionId,
			@Parameter(description = "Workflow status to queue by, e.g. PENDING_REVIEW for the approval queue") //
			@RequestParam(required = false) OrderStatus status) {

		if (prescriptionId == null && status == null) {
			throw new InvalidOrderException("Search for orders by a prescription or a status",
					Map.of("prescriptionId", "supply prescriptionId, status, or both"));
		}

		List<Order> found;
		if (prescriptionId != null && status != null) {
			found = this.service.getByPrescriptionId(prescriptionId).stream()
				.filter(order -> order.getStatus() == status)
				.toList();
		}
		else if (prescriptionId != null) {
			found = this.service.getByPrescriptionId(prescriptionId);
		}
		else {
			found = this.service.listByStatus(status);
		}

		return ApiResponse.ok(found.stream().map(OrderMapper::toResponse).toList());
	}

	@Operation(summary = "Approve an order for production",
			description = "The client's accept decision. The order must still be PENDING_REVIEW and the prescription "
					+ "it was built from must be VERIFIED, which is what stops an unreviewed or rejected prescription "
					+ "reaching the lab. One-way: there is no un-approve, so a decision cannot be taken back by "
					+ "resending the request.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order approved"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "Order or its prescription not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "The order has already been decided, or its prescription is not VERIFIED") })
	@PutMapping("/{orderId}/approve")
	public ApiResponse<OrderResponse> approve(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId) {
		return ApiResponse.ok(OrderMapper.toResponse(this.service.approve(orderId)));
	}

	@Operation(summary = "Reject an order",
			description = "The client's turn-down decision, with the reason recorded against the order. Independent "
					+ "of the prescription: rejecting an order does not reject the prescription it was built from, "
					+ "because the order can be turned down for a reason that leaves the prescription valid. The order "
					+ "does not have to have a verified prescription to be rejected.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order rejected"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "The reason is missing or longer than 500 characters"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "The order has already been decided") })
	@PutMapping("/{orderId}/reject")
	public ApiResponse<OrderResponse> reject(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId,
			@Parameter(description = "Why the order is being rejected", required = true) //
			@Valid @RequestBody RejectionRequest request) {
		return ApiResponse.ok(OrderMapper.toResponse(this.service.reject(orderId, request.reason())));
	}

	@Operation(summary = "Set the estimated receive date",
			description = "Corrects the estimated receive date, or withdraws it. The date quoted when the order was "
					+ "approved is worked out from a configured lab lead time, which cannot know that a frame came back "
					+ "in stock or that the lab is queueing, so the client can replace it. Sending no date withdraws the "
					+ "estimate. Only an order the shop has accepted carries a date, and a date in the past is refused "
					+ "because it would tell the customer their order is due before it was approved.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Receive date set, or withdrawn"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "The date is in the past"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "The order has not been approved, or was rejected") })
	@PutMapping("/{orderId}/receive-date")
	public ApiResponse<OrderResponse> setReceiveDate(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId,
			@Parameter(description = "Date the customer should expect the order; omit to withdraw the estimate", //
			required = true) @RequestBody ReceiveDateRequest request) {
		return ApiResponse.ok(OrderMapper.toResponse(this.service.setReceiveDate(orderId, request.receiveDate())));
	}

	/**
	 * The three production steps share one validation, so they are described once
	 * here and each endpoint only names the state it moves to.
	 */
	private ApiResponse<OrderResponse> productionStep(Long orderId, OrderStatus requested) {
		return switch (requested) {
			case PROCESSING -> ApiResponse.ok(OrderMapper.toResponse(this.service.markProcessing(orderId)));
			case READY -> ApiResponse.ok(OrderMapper.toResponse(this.service.markReady(orderId)));
			case DISPATCHED -> ApiResponse.ok(OrderMapper.toResponse(this.service.markDispatched(orderId)));
			default -> throw new InvalidOrderException("That is not a production step",
					Map.of("status", "must be PROCESSING, READY or DISPATCHED"));
		};
	}

	@Operation(summary = "Mark an order as being made",
			description = "Moves an approved order into production. The step has to be legal from the order's current "
					+ "status: an order cannot be processed before it is approved, and a finished or rejected order cannot "
					+ "be moved at all. Steps may be skipped, so an order already in stock can go straight to ready or "
					+ "dispatched without recording work that never happened. Nothing moves backwards.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Order is now being made"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "The order cannot be moved to PROCESSING from where it is") })
	@PutMapping("/{orderId}/processing")
	public ApiResponse<OrderResponse> markProcessing(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId) {
		return productionStep(orderId, OrderStatus.PROCESSING);
	}

	@Operation(summary = "Mark an order as ready to collect",
			description = "Records that the order is finished and waiting for the customer. Legal from an approved or "
					+ "in-progress order, and skipped steps are allowed.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Order is ready to collect"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "The order cannot be moved to READY from where it is") })
	@PutMapping("/{orderId}/ready")
	public ApiResponse<OrderResponse> markReady(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId) {
		return productionStep(orderId, OrderStatus.READY);
	}

	@Operation(summary = "Mark an order as dispatched",
			description = "Records that the order has been handed over or sent to the customer. This is the last step: "
					+ "a dispatched order cannot be moved again, and a rejected one never gets here.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Order is dispatched"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "The order cannot be moved to DISPATCHED from where it is") })
	@PutMapping("/{orderId}/dispatched")
	public ApiResponse<OrderResponse> markDispatched(
			@Parameter(description = "Order id", required = true) @PathVariable Long orderId) {
		return productionStep(orderId, OrderStatus.DISPATCHED);
	}
}
