package com.myopty.order.service;

import java.time.LocalDate;
import java.util.List;

import com.myopty.order.domain.Order;
import com.myopty.order.domain.OrderStatus;
import com.myopty.order.dto.OrderCreateRequest;

/**
 * Order placement and retrieval for the Order &amp; Prescription module.
 */
public interface OrderService {

	/**
	 * Places an order for the selected lens type against an existing prescription.
	 *
	 * <p>The order starts at {@code PENDING_REVIEW}: a customer chooses the order
	 * type but never the workflow state, and the prescription's own status is left
	 * untouched because the shop reviews it separately.
	 *
	 * @param request the order type the customer selected, the prescription to build
	 *                from and, when the catalog knows them, the frame and lens
	 * @throws com.myopty.order.exception.PrescriptionNotFoundException if the prescription does not exist
	 * @throws com.myopty.order.exception.OrderAlreadyExistsException   if that prescription has already been ordered
	 * @throws com.myopty.order.exception.InvalidOrderException         if the order type does not fit the prescription
	 */
	Order create(OrderCreateRequest request);

	/**
	 * @throws com.myopty.order.exception.OrderNotFoundException if no such order exists
	 */
	Order getById(Long orderId);

	/**
	 * Looks the order up from the prescription it was built from, which is how the
	 * customer finds the order for a prescription they already hold.
	 *
	 * <p>A list rather than a single order so this shares the shape of
	 * {@link #listByStatus}: {@code prescription_id} carries a unique key, so the
	 * answer is zero or one row, and a prescription that was never ordered is an
	 * empty list rather than a 404. That keeps {@code GET /api/orders} returning an
	 * array for every filter it accepts, so a client can parse one shape.
	 */
	List<Order> getByPrescriptionId(Long prescriptionId);

	/**
	 * The client's approval queue. Oldest first, capped at 100 rows.
	 *
	 * @throws com.myopty.order.exception.OrderNotFoundException never
	 */
	List<Order> listByStatus(OrderStatus status);

	/**
	 * Accepts the order for production once the prescription behind it has been
	 * verified.
	 *
	 * <p>The verification gate is the point of the method: an order can only be
	 * approved from {@code PENDING_REVIEW} and only against a {@code VERIFIED}
	 * prescription, so nothing unverified reaches the lab.
	 *
	 * <p>Approving also quotes the estimated receive date, so the customer learns
	 * when to expect the order from the same action that starts the work. A date
	 * already on the order is kept, because a date the shop set deliberately
	 * carries more information than one derived from a lead time.
	 *
	 * @throws com.myopty.order.exception.OrderNotFoundException          if no such order exists
	 * @throws com.myopty.order.exception.OrderNotReviewableException     if the order has already been decided
	 * @throws com.myopty.order.exception.PrescriptionNotFoundException   if the linked prescription is gone
	 * @throws com.myopty.order.exception.PrescriptionNotVerifiedException if that prescription is not VERIFIED
	 */
	Order approve(Long orderId);

	/**
	 * Turns the order down with a reason the client can act on.
	 *
	 * <p>Independent of the prescription: rejecting an order does not reject the
	 * prescription it was built from, because the order can be turned down for a
	 * reason that leaves the prescription perfectly valid.
	 *
	 * @param reason why the order is being rejected; must not be blank
	 * @throws com.myopty.order.exception.OrderNotFoundException      if no such order exists
	 * @throws com.myopty.order.exception.OrderNotReviewableException if the order has already been decided
	 * @throws com.myopty.order.exception.InvalidOrderException       if the reason is blank or too long
	 */
	Order reject(Long orderId, String reason);

	/**
	 * Corrects the estimated receive date, or withdraws it.
	 *
	 * <p>The estimate quoted on approval is worked out from a configured lead time,
	 * which cannot know that a frame came back in stock or that the lab is
	 * queueing. The client does, so this overwrites the date, and passing
	 * {@code null} clears it for when an estimate has to be withdrawn rather than
	 * replaced.
	 *
	 * <p>Only an order the shop has actually accepted carries a date. A date on an
	 * order still awaiting review would promise something for work that has not
	 * started, and a date on a rejected one is a contradiction.
	 *
	 * @param receiveDate the date the customer should expect the order, or
	 *                    {@code null} to withdraw the estimate; must not be in the past
	 * @throws com.myopty.order.exception.OrderNotFoundException   if no such order exists
	 * @throws com.myopty.order.exception.OrderNotApprovedException if the order has not been approved
	 * @throws com.myopty.order.exception.InvalidOrderException    if the date is in the past
	 */
	Order setReceiveDate(Long orderId, LocalDate receiveDate);

	/**
	 * Records that the order is being made in the lab.
	 *
	 * <p>One of the three production steps. Which of them are legal from the
	 * order's current status is decided by {@link OrderStatus#canAdvanceTo}, not by
	 * this method, so the three cannot disagree about the workflow between them.
	 *
	 * <p>The prescription is not read again: the order was already accepted against
	 * a verified one and that decision is one-way.
	 *
	 * @throws com.myopty.order.exception.OrderNotFoundException       if no such order exists
	 * @throws com.myopty.order.exception.OrderNotAdvancableException if the order cannot be moved to PROCESSING from where it is
	 */
	Order markProcessing(Long orderId);

	/**
	 * Records that the order is finished and waiting for the customer to collect.
	 *
	 * @throws com.myopty.order.exception.OrderNotFoundException       if no such order exists
	 * @throws com.myopty.order.exception.OrderNotAdvancableException if the order cannot be moved to READY from where it is
	 */
	Order markReady(Long orderId);

	/**
	 * Records that the order has been handed over or sent to the customer. This is
	 * the last step, and no order moves on from it.
	 *
	 * @throws com.myopty.order.exception.OrderNotFoundException       if no such order exists
	 * @throws com.myopty.order.exception.OrderNotAdvancableException if the order cannot be moved to DISPATCHED from where it is
	 */
	Order markDispatched(Long orderId);

}
