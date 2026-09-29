package com.myopty.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.myopty.order.config.LabProperties;
import com.myopty.order.domain.Order;
import com.myopty.order.domain.OrderStatus;
import com.myopty.order.domain.OrderType;
import com.myopty.order.domain.Prescription;
import com.myopty.order.domain.PrescriptionStatus;
import com.myopty.order.dto.OrderCreateRequest;
import com.myopty.order.exception.InvalidOrderException;
import com.myopty.order.exception.OrderAlreadyExistsException;
import com.myopty.order.exception.OrderNotApprovedException;
import com.myopty.order.exception.OrderNotAdvancableException;
import com.myopty.order.exception.OrderNotFoundException;
import com.myopty.order.exception.OrderNotReviewableException;
import com.myopty.order.exception.PrescriptionNotFoundException;
import com.myopty.order.exception.PrescriptionNotVerifiedException;
import com.myopty.order.repository.OrderRepository;
import com.myopty.order.repository.PrescriptionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * Order placement rules: the order type a customer may pick for a given
 * prescription, the one-to-one link to the prescription, and the ownership that
 * stops an order being placed against somebody else's prescription.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

	private static final Long PRESCRIPTION_ID = 12L;

	private static final Long CUSTOMER_ID = 7L;

	private static final Long ORDER_ID = 3L;

	/**
	 * Length of {@code progressive_order.rejection_reason}. Spelled out here rather
	 * than repeated as a literal, so a migration that widens the column is a
	 * one-line change here too.
	 */
	private static final int MAX_REASON = 500;

	@Mock
	private OrderRepository repository;

	@Mock
	private PrescriptionRepository prescriptions;

	/**
	 * Frozen so the quoted receive date is a value the test can state outright. With
	 * the system clock the expectation would be right for every run except the hour
	 * the date rolls over.
	 */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);

	private static final int SINGLE_VISION_LEAD_DAYS = 7;

	private static final int PROGRESSIVE_LEAD_DAYS = 14;

	private OrderServiceImpl service;

	@BeforeEach
	void setUp() {
		this.service = new OrderServiceImpl(this.repository, this.prescriptions, new LabProperties(
				Map.of(OrderType.SINGLE_VISION, SINGLE_VISION_LEAD_DAYS, OrderType.BIFOCAL, 10,
						OrderType.PROGRESSIVE, PROGRESSIVE_LEAD_DAYS)),
				CLOCK);
	}

	@Test
	void linksTheOrderToThePrescriptionAndQueuesReview() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		Order saved = this.service.create(request(OrderType.PROGRESSIVE));

		assertThat(saved.getOrderType()).isEqualTo(OrderType.PROGRESSIVE);
		assertThat(saved.getPrescriptionId()).isEqualTo(PRESCRIPTION_ID);
		assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER_ID);
		assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING_REVIEW);
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}

	/**
	 * A customer never picks the workflow state, so nothing in the request can move
	 * an order past the shop's review.
	 */
	@Test
	void neverLetsTheOrderStartAnywhereButReview() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		assertThat(this.service.create(request(OrderType.SINGLE_VISION)).getStatus())
			.isEqualTo(OrderStatus.PENDING_REVIEW);
	}

	@Test
	void recordsTheFrameAndLensWhenTheCustomerSuppliesThem() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		Order saved = this.service.create(new OrderCreateRequest(null, PRESCRIPTION_ID, OrderType.PROGRESSIVE, 4L, 9L));

		assertThat(saved.getFrameId()).isEqualTo(4L);
		assertThat(saved.getLensId()).isEqualTo(9L);
	}

	/**
	 * The catalog tables do not exist yet, so the frame and lens are optional:
	 * requiring them would make the whole feature unusable until another owner
	 * lands their migration.
	 */
	@Test
	void placesAnOrderWithoutAFrameOrLensWhenTheCatalogIsNotReachable() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		Order saved = this.service.create(request(OrderType.PROGRESSIVE));

		assertThat(saved.getFrameId()).isNull();
		assertThat(saved.getLensId()).isNull();
	}

	@Test
	void acceptsAProgressiveOrderOnAPrescriptionWithANearAddition() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		assertThat(this.service.create(request(OrderType.PROGRESSIVE)).getOrderType())
			.isEqualTo(OrderType.PROGRESSIVE);
	}

	@Test
	void acceptsASingleVisionOrderOnAPrescriptionWithNoNearAddition() {
		stubPrescription(plainPrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		assertThat(this.service.create(request(OrderType.SINGLE_VISION)).getOrderType())
			.isEqualTo(OrderType.SINGLE_VISION);
	}

	/**
	 * The rule that keeps the two stories consistent: the order type the customer
	 * selects has to be one the prescription can actually be made into, or the shop
	 * would queue a lens for the lab that has no near addition to cut.
	 */
	@Test
	void refusesAProgressiveOrderOnAPrescriptionWithNoNearAddition() {
		stubPrescription(plainPrescription());

		assertThatThrownBy(() -> this.service.create(request(OrderType.PROGRESSIVE)))
			.isInstanceOf(InvalidOrderException.class)
			.satisfies(ex -> assertThat(((InvalidOrderException) ex).getFieldErrors())
				.containsEntry("orderType", "a progressive lens needs a prescription with a near addition on both eyes"));
	}

	@Test
	void refusesABifocalOrderOnAPrescriptionWithNoNearAddition() {
		stubPrescription(plainPrescription());

		assertThatThrownBy(() -> this.service.create(request(OrderType.BIFOCAL))).isInstanceOf(InvalidOrderException.class)
			.satisfies(ex -> assertThat(((InvalidOrderException) ex).getFieldErrors()).containsKey("orderType"));
	}

	@Test
	void reportsEveryProblemWithAnOrderAtOnce() {
		stubPrescription(plainPrescription());

		assertThatThrownBy(
				() -> this.service.create(new OrderCreateRequest(99L, PRESCRIPTION_ID, OrderType.PROGRESSIVE, null, null)))
			.isInstanceOf(InvalidOrderException.class)
			.satisfies(ex -> assertThat(((InvalidOrderException) ex).getFieldErrors()).containsOnlyKeys("orderType",
					"customerId"));
	}

	@Test
	void refusesAnOrderAgainstSomebodyElsesPrescription() {
		stubPrescription(progressivePrescription());

		assertThatThrownBy(
				() -> this.service.create(new OrderCreateRequest(99L, PRESCRIPTION_ID, OrderType.PROGRESSIVE, null, null)))
			.isInstanceOf(InvalidOrderException.class)
			.satisfies(ex -> assertThat(((InvalidOrderException) ex).getFieldErrors())
				.containsEntry("customerId", "must match the customer the prescription belongs to"));
	}

	/**
	 * Until the shared user table lands a prescription has no owner, so refusing an
	 * order for that reason would block every customer. The order adopts the id the
	 * customer sent instead.
	 */
	@Test
	void adoptsTheCustomerWhenThePrescriptionDoesNotHaveOneYet() {
		Prescription unowned = plainPrescription();
		unowned.setCustomerId(null);
		stubPrescription(unowned);
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		Order saved = this.service
			.create(new OrderCreateRequest(CUSTOMER_ID, PRESCRIPTION_ID, OrderType.SINGLE_VISION, null, null));

		assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER_ID);
	}

	@Test
	void inheritsTheCustomerFromThePrescriptionWhenTheRequestOmitsIt() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		assertThat(this.service.create(request(OrderType.PROGRESSIVE)).getCustomerId()).isEqualTo(CUSTOMER_ID);
	}

	@Test
	void reportsAPrescriptionThatDoesNotExist() {
		when(this.prescriptions.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.create(new OrderCreateRequest(null, 404L, OrderType.SINGLE_VISION, null, null)))
			.isInstanceOf(PrescriptionNotFoundException.class)
			.hasMessage("Prescription 404 was not found");
	}

	/**
	 * The one-to-one link: a prescription that has already been ordered cannot be
	 * ordered again, or the shop would manufacture the same pair twice.
	 */
	@Test
	void refusesASecondOrderForTheSamePrescription() {
		stubPrescription(progressivePrescription());
		when(this.repository.findByPrescriptionId(PRESCRIPTION_ID)).thenReturn(Optional.of(new Order()));

		assertThatThrownBy(() -> this.service.create(request(OrderType.PROGRESSIVE)))
			.isInstanceOf(OrderAlreadyExistsException.class)
			.hasMessage("Prescription 12 has already been ordered");
	}

	/**
	 * The lookup above only spares the customer a constraint violation when nobody
	 * else is ordering at the same moment. When they are, the unique key is what
	 * rejects the insert, and the customer gets the same answer either way.
	 */
	@Test
	void reportsTheSameConflictWhenTwoOrdersRacePastTheCheck() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		when(this.repository.save(any(Order.class)))
			.thenThrow(new DuplicateKeyException("uk_progressive_order_prescription"));

		assertThatThrownBy(() -> this.service.create(request(OrderType.PROGRESSIVE)))
			.isInstanceOf(OrderAlreadyExistsException.class)
			.hasMessage("Prescription 12 has already been ordered");
	}

	@Test
	void writesNothingWhenTheOrderTypeDoesNotFit() {
		stubPrescription(plainPrescription());

		assertThatThrownBy(() -> this.service.create(request(OrderType.PROGRESSIVE)))
			.isInstanceOf(InvalidOrderException.class);

		verify(this.repository, never()).save(any(Order.class));
	}

	@Test
	void returnsAnOrderByItsId() {
		Order stored = new Order();
		stored.setOrderId(3L);
		when(this.repository.findById(3L)).thenReturn(Optional.of(stored));

		assertThat(this.service.getById(3L)).isSameAs(stored);
	}

	@Test
	void reportsAnUnknownOrderId() {
		when(this.repository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.getById(99L)).isInstanceOf(OrderNotFoundException.class)
			.hasMessage("Order 99 was not found");
	}

	/**
	 * The other direction of the link, and the reason the repository has a reverse
	 * lookup at all: a customer holding a prescription needs to find the order made
	 * from it without knowing the order id.
	 */
	@Test
	void findsTheOrderBuiltFromAPrescription() {
		Order stored = new Order();
		stored.setOrderId(3L);
		stored.setPrescriptionId(PRESCRIPTION_ID);
		when(this.repository.findByPrescriptionId(PRESCRIPTION_ID)).thenReturn(Optional.of(stored));

		assertThat(this.service.getByPrescriptionId(PRESCRIPTION_ID)).containsExactly(stored);
	}

	/**
	 * An unordered prescription is an empty result rather than a failure, so the
	 * lookup shares the collection shape of the status queue.
	 */
	@Test
	void reportsAPrescriptionThatHasNotBeenOrderedAsAnEmptyList() {
		when(this.repository.findByPrescriptionId(PRESCRIPTION_ID)).thenReturn(Optional.empty());

		assertThat(this.service.getByPrescriptionId(PRESCRIPTION_ID)).isEmpty();
	}

	/**
	 * Nothing in the order module may change the prescription it is built from, or
	 * the order would silently re-point itself at different optical values.
	 */
	@Test
	void leavesThePrescriptionUntouched() {
		stubPrescription(progressivePrescription());
		stubNoExistingOrder();
		stubSaveEchoingTheId();

		this.service.create(request(OrderType.PROGRESSIVE));

		verify(this.prescriptions, never()).save(any(Prescription.class));
	}

	/**
	 * Approving is the one place an order's status is set to APPROVED, and it is
	 * only reachable once the prescription behind it has been verified.
	 */
	@Test
	void approvesAnOrderWhosePrescriptionIsVerified() {
		stubOrder(pendingOrder());
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.VERIFIED));
		stubSaveEchoingTheId();

		Order approved = this.service.approve(ORDER_ID);

		assertThat(approved.getStatus()).isEqualTo(OrderStatus.APPROVED);
		assertThat(approved.hasRejectionReason()).isFalse();
	}

	/**
	 * The rule that makes reviewing a prescription load-bearing: an unreviewed
	 * prescription cannot be pushed into production by approving its order.
	 */
	@Test
	void refusesToApproveWhileThePrescriptionIsStillPending() {
		stubOrder(pendingOrder());
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.PENDING_REVIEW));

		assertThatThrownBy(() -> this.service.approve(ORDER_ID))
			.isInstanceOf(PrescriptionNotVerifiedException.class)
			.hasMessageContaining("PENDING_REVIEW");

		verify(this.repository, never()).save(any(Order.class));
	}

	@Test
	void refusesToApproveWhenThePrescriptionWasRejected() {
		stubOrder(pendingOrder());
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.REJECTED));

		assertThatThrownBy(() -> this.service.approve(ORDER_ID))
			.isInstanceOf(PrescriptionNotVerifiedException.class)
			.hasMessageContaining("REJECTED");
	}

	@Test
	void refusesToApproveAnOrderThatDoesNotExist() {
		when(this.repository.findById(ORDER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.approve(ORDER_ID)).isInstanceOf(OrderNotFoundException.class);
	}

	/**
	 * Reviewing is one-way, so an order that already carries a decision cannot be
	 * decided again by resending the request.
	 */
	@Test
	void refusesToApproveAnOrderAlreadyDecided() {
		Order decided = pendingOrder();
		decided.setStatus(OrderStatus.REJECTED);
		stubOrder(decided);

		assertThatThrownBy(() -> this.service.approve(ORDER_ID)).isInstanceOf(OrderNotReviewableException.class)
			.hasMessageContaining("REJECTED");

		verify(this.repository, never()).save(any(Order.class));
	}

	/**
	 * Rejecting does not need a verified prescription: turning an order down is
	 * exactly what a client does when the prescription is wrong, and the two
	 * decisions are deliberately independent.
	 */
	@Test
	void rejectsAnOrderWhosePrescriptionIsNotVerified() {
		stubOrder(pendingOrder());
		stubSaveEchoingTheId();

		Order rejected = this.service.reject(ORDER_ID, "frame out of stock");

		assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
		assertThat(rejected.getRejectionReason()).isEqualTo("frame out of stock");
		verify(this.prescriptions, never()).findById(any());
	}

	@Test
	void trimsTheRejectionReason() {
		stubOrder(pendingOrder());
		stubSaveEchoingTheId();

		assertThat(this.service.reject(ORDER_ID, "  frame out of stock  ").getRejectionReason())
			.isEqualTo("frame out of stock");
	}

	/**
	 * A rejection nobody can act on is not a decision, so a blank reason is refused
	 * rather than stored.
	 */
	@Test
	void refusesARejectionWithNoReason() {
		stubOrder(pendingOrder());

		assertThatThrownBy(() -> this.service.reject(ORDER_ID, "   ")).isInstanceOf(InvalidOrderException.class)
			.hasMessageContaining("reason");
	}

	@Test
	void refusesARejectionReasonLongerThanTheColumn() {
		stubOrder(pendingOrder());

		assertThatThrownBy(() -> this.service.reject(ORDER_ID, "x".repeat(MAX_REASON + 1)))
			.isInstanceOf(InvalidOrderException.class)
			.extracting(ex -> ((InvalidOrderException) ex).getFieldErrors().get("reason"))
			.isEqualTo("must be at most " + MAX_REASON + " characters");
	}

	@Test
	void refusesToRejectAnOrderAlreadyDecided() {
		Order decided = pendingOrder();
		decided.setStatus(OrderStatus.APPROVED);
		stubOrder(decided);

		assertThatThrownBy(() -> this.service.reject(ORDER_ID, "too late"))
			.isInstanceOf(OrderNotReviewableException.class);
	}

	/**
	 * Approving writes only the order. Rewriting the prescription here would let a
	 * client mark a prescription verified as a side effect of approving its order,
	 * which would defeat the review entirely.
	 */
	@Test
	void approvesWithoutTouchingThePrescription() {
		stubOrder(pendingOrder());
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.VERIFIED));
		stubSaveEchoingTheId();

		this.service.approve(ORDER_ID);

		verify(this.prescriptions, never()).save(any(Prescription.class));
	}

	@Test
	void queuesOrdersByStatus() {
		Order first = pendingOrder();
		when(this.repository.findTop100ByStatusOrderByCreatedAtAscOrderIdAsc(OrderStatus.PENDING_REVIEW))
			.thenReturn(List.of(first));

		assertThat(this.service.listByStatus(OrderStatus.PENDING_REVIEW)).containsExactly(first);
	}

	/**
	 * The date the estimate produces is checked against the frozen clock rather than
	 * "about two weeks out", so a change to the lead time or the rule shows up here
	 * instead of being read as a slightly different number.
	 */
	@Test
	void quotesAReceiveDateWhenTheOrderIsApproved() {
		stubOrder(pendingOrder());
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.VERIFIED));
		stubSaveEchoingTheId();

		Order approved = this.service.approve(ORDER_ID);

		// Clock is fixed at 2026-03-10 and a progressive order is quoted at 14 days.
		assertThat(approved.getReceiveDate()).isEqualTo(LocalDate.of(2026, 3, 24));
	}

	/**
	 * A progressive lens takes longer in the lab than a single-vision one, so the
	 * lead time has to follow the order type rather than be one number for the shop.
	 */
	@Test
	void quotesTheLeadTimeForTheOrderTypeThatWasOrdered() {
		Order singleVision = pendingOrder();
		singleVision.setOrderType(OrderType.SINGLE_VISION);
		stubOrder(singleVision);
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.VERIFIED));
		stubSaveEchoingTheId();

		Order approved = this.service.approve(ORDER_ID);

		assertThat(approved.getReceiveDate()).isEqualTo(LocalDate.of(2026, 3, 17));
	}

	/**
	 * A date the shop set deliberately carries more information than a number
	 * derived from a lead time, so approving must not overwrite it.
	 */
	@Test
	void keepsAReceiveDateTheShopAlreadySet() {
		Order quoted = pendingOrder();
		quoted.setReceiveDate(LocalDate.of(2026, 4, 2));
		stubOrder(quoted);
		stubPrescription(prescriptionWithStatus(PrescriptionStatus.VERIFIED));
		stubSaveEchoingTheId();

		Order approved = this.service.approve(ORDER_ID);

		assertThat(approved.getReceiveDate()).isEqualTo(LocalDate.of(2026, 4, 2));
	}

	/**
	 * Turning an order down promises nothing, so a rejected order must not be given
	 * a date the customer could read as a commitment.
	 */
	@Test
	void quotesNoReceiveDateOnARejectedOrder() {
		stubOrder(pendingOrder());
		stubSaveEchoingTheId();

		Order rejected = this.service.reject(ORDER_ID, "frame out of stock");

		assertThat(rejected.hasReceiveDate()).isFalse();
	}

	@Test
	void correctsTheQuotedReceiveDate() {
		Order approved = approvedOrder();
		stubOrder(approved);
		stubSaveEchoingTheId();

		Order updated = this.service.setReceiveDate(ORDER_ID, LocalDate.of(2026, 3, 30));

		assertThat(updated.getReceiveDate()).isEqualTo(LocalDate.of(2026, 3, 30));
	}

	/**
	 * Withdrawing an estimate is a different act from replacing one: a shop that
	 * cannot stand behind its date clears it, and {@code null} says so explicitly.
	 */
	@Test
	void withdrawsTheEstimateWhenGivenNoDate() {
		Order approved = approvedOrder();
		approved.setReceiveDate(LocalDate.of(2026, 3, 24));
		stubOrder(approved);
		stubSaveEchoingTheId();

		Order updated = this.service.setReceiveDate(ORDER_ID, null);

		assertThat(updated.hasReceiveDate()).isFalse();
	}

	/**
	 * The frozen clock's date is the boundary, and quoting a customer an order that
	 * was already due before it was approved is the mistake worth refusing.
	 */
	@Test
	void refusesAReceiveDateInThePast() {
		stubOrder(approvedOrder());

		assertThatThrownBy(() -> this.service.setReceiveDate(ORDER_ID, LocalDate.of(2026, 3, 9)))
			.isInstanceOf(InvalidOrderException.class)
			.satisfies(thrown -> assertThat(((InvalidOrderException) thrown).getFieldErrors())
				.containsEntry("receiveDate", "must be today or later"));

		verify(this.repository, never()).save(any(Order.class));
	}

	@Test
	void acceptsTodayAsTheReceiveDate() {
		stubOrder(approvedOrder());
		stubSaveEchoingTheId();

		Order updated = this.service.setReceiveDate(ORDER_ID, LocalDate.of(2026, 3, 10));

		assertThat(updated.getReceiveDate()).isEqualTo(LocalDate.of(2026, 3, 10));
	}

	@Test
	void refusesAReceiveDateWhileTheOrderIsStillAwaitingReview() {
		stubOrder(pendingOrder());

		assertThatThrownBy(() -> this.service.setReceiveDate(ORDER_ID, LocalDate.of(2026, 3, 30)))
			.isInstanceOf(OrderNotApprovedException.class)
			.hasMessageContaining("PENDING_REVIEW");

		verify(this.repository, never()).save(any(Order.class));
	}

	@Test
	void refusesAReceiveDateOnARejectedOrder() {
		Order rejected = approvedOrder();
		rejected.setStatus(OrderStatus.REJECTED);
		stubOrder(rejected);

		assertThatThrownBy(() -> this.service.setReceiveDate(ORDER_ID, LocalDate.of(2026, 3, 30)))
			.isInstanceOf(OrderNotApprovedException.class)
			.hasMessageContaining("REJECTED");
	}

	@Test
	void reportsAnUnknownOrderWhenSettingTheReceiveDate() {
		when(this.repository.findById(ORDER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.setReceiveDate(ORDER_ID, LocalDate.of(2026, 3, 30)))
			.isInstanceOf(OrderNotFoundException.class);
	}

	@Test
	void reportsAnUnknownOrderWhenAdvancingIt() {
		when(this.repository.findById(ORDER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.markProcessing(ORDER_ID)).isInstanceOf(OrderNotFoundException.class);
	}

	@Test
	void marksAnApprovedOrderAsProcessing() {
		stubOrder(orderIn(OrderStatus.APPROVED));
		stubSaveEchoingTheId();

		Order moved = this.service.markProcessing(ORDER_ID);

		assertThat(moved.getStatus()).isEqualTo(OrderStatus.PROCESSING);
	}

	@Test
	void marksAnOrderAsReady() {
		stubOrder(orderIn(OrderStatus.PROCESSING));
		stubSaveEchoingTheId();

		Order moved = this.service.markReady(ORDER_ID);

		assertThat(moved.getStatus()).isEqualTo(OrderStatus.READY);
	}

	@Test
	void marksAnOrderAsDispatched() {
		stubOrder(orderIn(OrderStatus.READY));
		stubSaveEchoingTheId();

		Order moved = this.service.markDispatched(ORDER_ID);

		assertThat(moved.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
	}

	/**
	 * A frame that was already in stock has not been through the lab, and making the
	 * client record a processing step that never happened would be a worse record
	 * than the skip.
	 */
	@Test
	void letsAnApprovedOrderSkipStraightToDispatched() {
		stubOrder(orderIn(OrderStatus.APPROVED));
		stubSaveEchoingTheId();

		Order moved = this.service.markDispatched(ORDER_ID);

		assertThat(moved.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
	}

	/**
	 * The reason the review decision and the production steps are separate: work must
	 * never start on an order the shop has not accepted.
	 */
	@Test
	void refusesToProcessAnOrderStillAwaitingReview() {
		stubOrder(orderIn(OrderStatus.PENDING_REVIEW));

		assertThatThrownBy(() -> this.service.markProcessing(ORDER_ID))
			.isInstanceOf(OrderNotAdvancableException.class)
			.hasMessageContaining("PENDING_REVIEW")
			.hasMessageContaining("PROCESSING");

		verify(this.repository, never()).save(any(Order.class));
	}

	@Test
	void neverMovesAnOrderBackwards() {
		stubOrder(orderIn(OrderStatus.READY));

		assertThatThrownBy(() -> this.service.markProcessing(ORDER_ID))
			.isInstanceOf(OrderNotAdvancableException.class)
			.hasMessageContaining("READY");
	}

	@Test
	void neverMovesADispatchedOrderAgain() {
		stubOrder(orderIn(OrderStatus.DISPATCHED));

		assertThatThrownBy(() -> this.service.markReady(ORDER_ID))
			.isInstanceOf(OrderNotAdvancableException.class)
			.hasMessageContaining("DISPATCHED");

		verify(this.repository, never()).save(any(Order.class));
	}

	/**
	 * A rejected order never entered production, so no step applies to it however
	 * many times a client tries.
	 */
	@Test
	void neverMovesARejectedOrderIntoProduction() {
		stubOrder(orderIn(OrderStatus.REJECTED));

		for (Runnable attempt : List.<Runnable>of(() -> this.service.markProcessing(ORDER_ID),
				() -> this.service.markReady(ORDER_ID), () -> this.service.markDispatched(ORDER_ID))) {
			assertThatThrownBy(attempt::run).isInstanceOf(OrderNotAdvancableException.class)
				.hasMessageContaining("REJECTED");
		}

		verify(this.repository, never()).save(any(Order.class));
	}

	/**
	 * The order was already accepted against a verified prescription, and that
	 * decision cannot be taken back, so advancing reads the order and nothing else.
	 */
	@Test
	void advancesWithoutReadingThePrescriptionAgain() {
		stubOrder(orderIn(OrderStatus.APPROVED));
		stubSaveEchoingTheId();

		this.service.markProcessing(ORDER_ID);

		verify(this.prescriptions, never()).findById(any());
	}

	private static Order orderIn(OrderStatus status) {
		Order order = pendingOrder();
		order.setStatus(status);
		return order;
	}

	private void stubOrder(Order order) {
		when(this.repository.findById(ORDER_ID)).thenReturn(Optional.of(order));
	}

	private void stubPrescription(Prescription prescription) {
		when(this.prescriptions.findById(PRESCRIPTION_ID)).thenReturn(Optional.of(prescription));
	}
	private void stubNoExistingOrder() {
		when(this.repository.findByPrescriptionId(PRESCRIPTION_ID)).thenReturn(Optional.empty());
	}

	/**
	 * Echoes the saved order back the way Spring Data JDBC does, assigning the
	 * generated key, so the test can assert on what was written.
	 */
	private void stubSaveEchoingTheId() {
		when(this.repository.save(any(Order.class))).thenAnswer(invocation -> {
			Order order = invocation.getArgument(0);
			order.setOrderId(3L);
			return order;
		});
	}

	private static OrderCreateRequest request(OrderType orderType) {
		return new OrderCreateRequest(CUSTOMER_ID, PRESCRIPTION_ID, orderType, null, null);
	}

	private static Prescription plainPrescription() {
		Prescription prescription = new Prescription();
		prescription.setPrescriptionId(PRESCRIPTION_ID);
		prescription.setCustomerId(CUSTOMER_ID);
		prescription.setProgressive(false);
		return prescription;
	}

	private static Prescription progressivePrescription() {
		Prescription prescription = plainPrescription();
		prescription.setProgressive(true);
		return prescription;
	}

	private static Order pendingOrder() {
		Order order = new Order();
		order.setOrderId(ORDER_ID);
		order.setPrescriptionId(PRESCRIPTION_ID);
		order.setOrderType(OrderType.PROGRESSIVE);
		order.setStatus(OrderStatus.PENDING_REVIEW);
		return order;
	}

	private static Order approvedOrder() {
		Order order = pendingOrder();
		order.setStatus(OrderStatus.APPROVED);
		return order;
	}

	private static Prescription prescriptionWithStatus(PrescriptionStatus status) {
		Prescription prescription = progressivePrescription();
		prescription.setStatus(status);
		return prescription;
	}

}
