package com.myopty.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.myopty.order.domain.Order;
import com.myopty.order.domain.OrderStatus;
import com.myopty.order.domain.OrderType;
import com.myopty.order.exception.InvalidOrderException;
import com.myopty.order.exception.OrderAlreadyExistsException;
import com.myopty.order.exception.OrderExceptionHandler;
import com.myopty.order.exception.OrderNotApprovedException;
import com.myopty.order.exception.OrderNotAdvancableException;
import com.myopty.order.exception.OrderNotFoundException;
import com.myopty.order.exception.OrderNotReviewableException;
import com.myopty.order.exception.PrescriptionNotFoundException;
import com.myopty.order.exception.PrescriptionNotVerifiedException;
import com.myopty.order.service.OrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Endpoint behaviour for placing and retrieving an order: the JSON contract, the
 * documented response envelope and the documented error codes. In particular the
 * codes the order form on the client keys off, including the one for an order type
 * the API does not recognise.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

	private static final String VALID_REQUEST = """
			{
			  "customerId": 7,
			  "prescriptionId": 12,
			  "orderType": "PROGRESSIVE",
			  "frameId": 4,
			  "lensId": 9
			}
			""";

	@Mock
	private OrderService service;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(this.service))
			.setControllerAdvice(new OrderExceptionHandler())
			.build();
	}

	@Test
	void acceptsAnOrderAndEchoesTheSelectedType() throws Exception {
		when(this.service.create(any())).thenReturn(order());

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(3L))
			.andExpect(jsonPath("$.data.customerId").value(7L))
			.andExpect(jsonPath("$.data.prescriptionId").value(12L))
			.andExpect(jsonPath("$.data.frameId").value(4L))
			.andExpect(jsonPath("$.data.lensId").value(9L))
			.andExpect(jsonPath("$.data.orderType").value("PROGRESSIVE"))
			.andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	/**
	 * No receive date has been calculated yet, so the field is absent rather than
	 * carrying a value the shop never promised.
	 */
	@Test
	void omitsTheReceiveDateUntilTheShopHasQuotedOne() throws Exception {
		Order unquoted = order();
		unquoted.setReceiveDate(null);
		when(this.service.create(any())).thenReturn(unquoted);

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.receiveDate").doesNotExist());
	}

	@Test
	void carriesTheReceiveDateOnceTheShopHasQuotedOne() throws Exception {
		when(this.service.create(any())).thenReturn(order());

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.receiveDate").value("2026-10-20"));
	}

	@Test
	void acceptsAnOrderThatNamesTheFrameAndLens() throws Exception {
		when(this.service.create(any())).thenReturn(order());

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
				{ "prescriptionId": 12, "orderType": "BIFOCAL" }
				""")).andExpect(status().isCreated());
	}

	@Test
	void rejectsAnOrderWithoutAPrescription() throws Exception {
		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
					{ "orderType": "PROGRESSIVE" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.fieldErrors.prescriptionId").exists());
	}

	/**
	 * The order type is the one field with no sensible default, because defaulting
	 * it would decide for the customer which workflow their order enters.
	 */
	@Test
	void rejectsAnOrderWithoutAnOrderType() throws Exception {
		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
					{ "prescriptionId": 12 }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.fieldErrors.orderType").exists());
	}

	@Test
	void rejectsAnOrderTypeItDoesNotRecognise() throws Exception {
		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
					{ "prescriptionId": 12, "orderType": "progressiv" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void rejectsANonPositivePrescriptionId() throws Exception {
		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
					{ "prescriptionId": 0, "orderType": "SINGLE_VISION" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.fieldErrors.prescriptionId").exists());
	}

	@Test
	void reportsAnOrderTypeThatDoesNotFitThePrescription() throws Exception {
		when(this.service.create(any())).thenThrow(new InvalidOrderException("Some order values are not valid",
				Map.of("orderType", "a progressive lens needs a prescription with a near addition on both eyes")));

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_ORDER"))
			.andExpect(jsonPath("$.error.fieldErrors.orderType").exists());
	}

	@Test
	void reportsAPrescriptionThatDoesNotExist() throws Exception {
		when(this.service.create(any())).thenThrow(new PrescriptionNotFoundException(404L));

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("PRESCRIPTION_NOT_FOUND"));
	}

	@Test
	void reportsASecondOrderForTheSamePrescriptionAsAConflict() throws Exception {
		when(this.service.create(any())).thenThrow(new OrderAlreadyExistsException(12L));

		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_EXISTS"))
			.andExpect(jsonPath("$.error.fieldErrors.prescriptionId").exists());
	}

	@Test
	void returnsASingleOrder() throws Exception {
		when(this.service.getById(3L)).thenReturn(order());

		this.mockMvc.perform(get("/api/orders/3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(3L))
			.andExpect(jsonPath("$.data.orderType").value("PROGRESSIVE"));
	}

	@Test
	void reportsAnUnknownOrder() throws Exception {
		when(this.service.getById(99L)).thenThrow(new OrderNotFoundException(99L));

		this.mockMvc.perform(get("/api/orders/99"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("Order 99 was not found"));
	}

	/**
	 * The other direction of the link, so a customer holding a prescription can
	 * find the order made from it. An array rather than a bare object so this
	 * endpoint shares one shape with the status queue.
	 */
	@Test
	void findsTheOrderBuiltFromAPrescription() throws Exception {
		when(this.service.getByPrescriptionId(12L)).thenReturn(List.of(order()));

		this.mockMvc.perform(get("/api/orders").param("prescriptionId", "12"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].prescriptionId").value(12L));
	}

	/**
	 * A prescription that was never ordered is an empty result, not a 404: the
	 * filter is a query and the honest answer is "none", which keeps every filter on
	 * this endpoint returning the same array.
	 */
	@Test
	void reportsAPrescriptionThatHasNotBeenOrderedAsAnEmptyList() throws Exception {
		when(this.service.getByPrescriptionId(12L)).thenReturn(List.of());

		this.mockMvc.perform(get("/api/orders").param("prescriptionId", "12"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	void queuesOrdersByStatus() throws Exception {
		when(this.service.listByStatus(OrderStatus.PENDING_REVIEW)).thenReturn(List.of(order()));

		this.mockMvc.perform(get("/api/orders").param("status", "PENDING_REVIEW"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1));
	}

	/**
	 * Both filters narrow to rows matching each, rather than one silently winning.
	 */
	@Test
	void narrowsWhenBothFiltersAreGiven() throws Exception {
		when(this.service.getByPrescriptionId(12L)).thenReturn(List.of(order()));

		this.mockMvc.perform(get("/api/orders").param("prescriptionId", "12").param("status", "PENDING_REVIEW"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1));

		verify(this.service).getByPrescriptionId(12L);
		verify(this.service, never()).listByStatus(any());
	}

	@Test
	void dropsRowsThatDoNotMatchTheStatusWhenBothFiltersAreGiven() throws Exception {
		when(this.service.getByPrescriptionId(12L)).thenReturn(List.of(approvedOrder()));

		this.mockMvc.perform(get("/api/orders").param("prescriptionId", "12").param("status", "PENDING_REVIEW"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	/**
	 * An unfiltered list would return every order ever placed, which is not a view
	 * anybody asks for and would make the 100-row cap arbitrary.
	 */
	@Test
	void refusesASearchWithNoFilter() throws Exception {
		this.mockMvc.perform(get("/api/orders"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_ORDER"));
	}

	@Test
	void refusesAStatusThatIsNotAnOrderStatus() throws Exception {
		this.mockMvc.perform(get("/api/orders").param("status", "ON_HOLD"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void approvesAnOrder() throws Exception {
		when(this.service.approve(3L)).thenReturn(approvedOrder());

		this.mockMvc.perform(put("/api/orders/3/approve"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("APPROVED"));
	}

	@Test
	void refusesToApproveAnOrderAlreadyDecided() throws Exception {
		when(this.service.approve(3L)).thenThrow(new OrderNotReviewableException(3L, "APPROVED"));

		this.mockMvc.perform(put("/api/orders/3/approve"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_REVIEWABLE"));
	}

	/**
	 * The rule that makes reviewing a prescription matter: an order cannot be
	 * approved until the prescription behind it has been verified.
	 */
	@Test
	void refusesToApproveAnOrderWhosePrescriptionIsNotVerified() throws Exception {
		when(this.service.approve(3L)).thenThrow(new PrescriptionNotVerifiedException(12L, "PENDING_REVIEW"));

		this.mockMvc.perform(put("/api/orders/3/approve"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRESCRIPTION_NOT_VERIFIED"));
	}

	@Test
	void rejectsAnOrderWithAReason() throws Exception {
		when(this.service.reject(3L, "frame out of stock")).thenReturn(rejectedOrder());

		this.mockMvc.perform(put("/api/orders/3/reject").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"frame out of stock\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.rejectionReason").value("frame out of stock"));
	}

	@Test
	void refusesARejectionWithNoReason() throws Exception {
		this.mockMvc.perform(put("/api/orders/3/reject").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void refusesARejectionWithNoBody() throws Exception {
		this.mockMvc.perform(put("/api/orders/3/reject")).andExpect(status().isBadRequest());
	}

	@Test
	void refusesToRejectAnOrderAlreadyDecided() throws Exception {
		when(this.service.reject(3L, "too late")).thenThrow(new OrderNotReviewableException(3L, "REJECTED"));

		this.mockMvc.perform(put("/api/orders/3/reject").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"too late\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_REVIEWABLE"));
	}

	/**
	 * The reason is only absent from the payload while the order is not rejected,
	 * so a fresh order must not carry an empty string that reads as a reason.
	 */
	@Test
	void omitsTheReasonOnAnOrderThatHasNotBeenRejected() throws Exception {
		when(this.service.getById(3L)).thenReturn(order());

		this.mockMvc.perform(get("/api/orders/3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.rejectionReason").doesNotExist());
	}

	@Test
	void refusesANonNumericOrderId() throws Exception {
		this.mockMvc.perform(get("/api/orders/abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void setsTheReceiveDate() throws Exception {
		Order dated = approvedOrder();
		dated.setReceiveDate(LocalDate.of(2026, 10, 27));
		when(this.service.setReceiveDate(3L, LocalDate.of(2026, 10, 27))).thenReturn(dated);

		this.mockMvc
			.perform(put("/api/orders/3/receive-date").contentType(MediaType.APPLICATION_JSON)
				.content("{\"receiveDate\":\"2026-10-27\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.receiveDate").value("2026-10-27"));
	}

	/**
	 * A body with no date is the documented way to withdraw an estimate, so the
	 * service is handed a null rather than the request being rejected.
	 */
	@Test
	void withdrawsTheEstimateWhenTheBodyCarriesNoDate() throws Exception {
		Order withdrawn = approvedOrder();
		withdrawn.setReceiveDate(null);
		when(this.service.setReceiveDate(3L, null)).thenReturn(withdrawn);

		this.mockMvc
			.perform(put("/api/orders/3/receive-date").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.receiveDate").doesNotExist());
	}

	@Test
	void refusesAReceiveDateInThePast() throws Exception {
		when(this.service.setReceiveDate(3L, LocalDate.of(2026, 1, 1)))
			.thenThrow(new InvalidOrderException("A receive date cannot be in the past",
					Map.of("receiveDate", "must be today or later")));

		this.mockMvc
			.perform(put("/api/orders/3/receive-date").contentType(MediaType.APPLICATION_JSON)
				.content("{\"receiveDate\":\"2026-01-01\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_ORDER"))
			.andExpect(jsonPath("$.error.fieldErrors.receiveDate").value("must be today or later"));
	}

	@Test
	void refusesAReceiveDateOnAnOrderThatHasNotBeenApproved() throws Exception {
		when(this.service.setReceiveDate(3L, LocalDate.of(2026, 10, 27)))
			.thenThrow(new OrderNotApprovedException(3L, "PENDING_REVIEW"));

		this.mockMvc
			.perform(put("/api/orders/3/receive-date").contentType(MediaType.APPLICATION_JSON)
				.content("{\"receiveDate\":\"2026-10-27\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_APPROVED"));
	}

	/**
	 * A date the JSON reader cannot parse is a 400 rather than a 500, so a client
	 * typing the format wrong is told so instead of being told to retry.
	 */
	@Test
	void refusesAReceiveDateItCannotRead() throws Exception {
		this.mockMvc
			.perform(put("/api/orders/3/receive-date").contentType(MediaType.APPLICATION_JSON)
				.content("{\"receiveDate\":\"27/10/2026\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void reportsAnUnknownOrderWhenSettingTheReceiveDate() throws Exception {
		when(this.service.setReceiveDate(3L, LocalDate.of(2026, 10, 27)))
			.thenThrow(new OrderNotFoundException(3L));

		this.mockMvc
			.perform(put("/api/orders/3/receive-date").contentType(MediaType.APPLICATION_JSON)
				.content("{\"receiveDate\":\"2026-10-27\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void marksAnOrderAsProcessing() throws Exception {
		when(this.service.markProcessing(3L)).thenReturn(orderIn(OrderStatus.PROCESSING));

		this.mockMvc.perform(put("/api/orders/3/processing"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("PROCESSING"));
	}

	@Test
	void marksAnOrderAsReady() throws Exception {
		when(this.service.markReady(3L)).thenReturn(orderIn(OrderStatus.READY));

		this.mockMvc.perform(put("/api/orders/3/ready"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("READY"));
	}

	@Test
	void marksAnOrderAsDispatched() throws Exception {
		when(this.service.markDispatched(3L)).thenReturn(orderIn(OrderStatus.DISPATCHED));

		this.mockMvc.perform(put("/api/orders/3/dispatched"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DISPATCHED"));
	}

	/**
	 * Each endpoint names the state it moves to, so a request cannot push an order
	 * somewhere the workflow has not reached: the three routes are the only way in.
	 */
	@Test
	void refusesToProcessAnOrderStillAwaitingReview() throws Exception {
		when(this.service.markProcessing(3L))
			.thenThrow(new OrderNotAdvancableException(3L, "PENDING_REVIEW", OrderStatus.PROCESSING));

		this.mockMvc.perform(put("/api/orders/3/processing"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_ADVANCABLE"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("PROCESSING")));
	}

	@Test
	void refusesToMoveADispatchedOrderAgain() throws Exception {
		when(this.service.markReady(3L))
			.thenThrow(new OrderNotAdvancableException(3L, "DISPATCHED", OrderStatus.READY));

		this.mockMvc.perform(put("/api/orders/3/ready"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_ADVANCABLE"));
	}

	@Test
	void reportsAnUnknownOrderWhenAdvancingIt() throws Exception {
		when(this.service.markProcessing(3L)).thenThrow(new OrderNotFoundException(3L));

		this.mockMvc.perform(put("/api/orders/3/processing"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
	}

	/**
	 * The production statuses are in the enum and the status column already, so the
	 * queue endpoint can already ask for them. This is what a client would see while
	 * working the bench.
	 */
	@Test
	void queuesOrdersWaitingToBeProcessed() throws Exception {
		when(this.service.listByStatus(OrderStatus.PROCESSING)).thenReturn(List.of(orderIn(OrderStatus.PROCESSING)));

		this.mockMvc.perform(get("/api/orders").param("status", "PROCESSING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].status").value("PROCESSING"));
	}

	private static Order orderIn(OrderStatus status) {
		Order order = order();
		order.setStatus(status);
		return order;
	}

	private static Order order() {
		Order order = new Order();
		order.setOrderId(3L);
		order.setCustomerId(7L);
		order.setPrescriptionId(12L);
		order.setFrameId(4L);
		order.setLensId(9L);
		order.setOrderType(OrderType.PROGRESSIVE);
		order.setStatus(OrderStatus.PENDING_REVIEW);
		order.setReceiveDate(LocalDate.of(2026, 10, 20));
		order.setCreatedAt(Instant.parse("2026-09-29T10:15:30Z"));
		order.setUpdatedAt(Instant.parse("2026-09-29T10:15:30Z"));
		return order;
	}

	/**
	 * The same order once it has been decided, for the cases that need a status
	 * other than {@code PENDING_REVIEW}.
	 */
	private static Order approvedOrder() {
		Order order = order();
		order.setStatus(OrderStatus.APPROVED);
		return order;
	}

	private static Order rejectedOrder() {
		Order order = order();
		order.setStatus(OrderStatus.REJECTED);
		order.setRejectionReason("frame out of stock");
		return order;
	}

}
