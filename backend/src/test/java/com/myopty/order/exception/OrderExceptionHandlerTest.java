package com.myopty.order.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.myopty.order.domain.OrderStatus;
import com.myopty.order.dto.ApiResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * The error contract of the module: every failure a customer can trigger has to
 * come back in the standard envelope with a stable code, because the form on the
 * client keys off those codes.
 */
class OrderExceptionHandlerTest {

	private final OrderExceptionHandler handler = new OrderExceptionHandler();

	@Test
	void reportsAMissingPrescription() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleNotFound(new PrescriptionNotFoundException(99L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().success()).isFalse();
		assertThat(response.getBody().error().code()).isEqualTo("PRESCRIPTION_NOT_FOUND");
		assertThat(response.getBody().error().message()).isEqualTo("Prescription 99 was not found");
	}

	@Test
	void reportsAMissingDocumentAsNotFoundRatherThanAStorageOutage() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleDocumentNotFound(new PrescriptionDocumentNotFoundException(42L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("DOCUMENT_NOT_FOUND");
		assertThat(response.getBody().error().message()).isEqualTo("Prescription 42 has no document attached");
	}

	@Test
	void reportsCrossFieldOpticalProblemsWithTheOffendingFields() {		InvalidPrescriptionException ex = new InvalidPrescriptionException("The optical values are inconsistent",
				Map.of("rightEye.axis", "an axis is required when the cylinder is not zero"));

		ResponseEntity<ApiResponse<Void>> response = this.handler.handleInvalidPrescription(ex);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_PRESCRIPTION");
		assertThat(response.getBody().error().fieldErrors()).containsEntry("rightEye.axis",
				"an axis is required when the cylinder is not zero");
	}

	@Test
	void reportsAnUnusableDocumentAgainstTheDocumentField() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleInvalidDocument(new InvalidPrescriptionDocumentException("Only JPEG, PNG, WebP, HEIC and PDF are allowed"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_DOCUMENT");
		assertThat(response.getBody().error().fieldErrors()).containsKey("document");
	}

	@Test
	void namesTheMissingPart() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleMissingPart(new MissingServletRequestPartException("document"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("MISSING_PART");
		assertThat(response.getBody().error().fieldErrors()).containsKey("document");
	}

	@Test
	void collectsEveryInvalidFieldOfTheRequestPart() {
		BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "prescription");
		binding.addError(fieldError("rightEye.sphere", "must be between -30.00 and 30.00"));
		binding.addError(fieldError("leftEye.cylinder", "must be between -10.00 and 10.00"));
		binding.addError(new ObjectError("prescription", new String[] { "rightEye" }, null, "the right eye is required"));

		ResponseEntity<ApiResponse<Void>> response = this.handler.handleValidation(new BindException(binding));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
		assertThat(response.getBody().error().fieldErrors()).containsOnlyKeys("rightEye.sphere", "leftEye.cylinder",
				"prescription");
	}

	@Test
	void reportsAnOversizedUploadSeparatelyFromOtherBadRequests() {
		ResponseEntity<ApiResponse<Void>> response = this.handler.handleTooLarge(new MaxUploadSizeExceededException(10L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("DOCUMENT_TOO_LARGE");
	}

	@Test
	void hidesTheDetailOfAMalformedRequest() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleMalformedRequest(new MissingServletRequestParameterException("id", "java.lang.Long"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(response.getBody().error().fieldErrors()).isNullOrEmpty();
	}

	/**
	 * Object storage is downstream of the API, so its failures must look retryable
	 * rather than like a fault in what the customer submitted.
	 */
	@Test
	void reportsStorageTroubleAsBadGateway() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleStorageFailure(new DocumentStorageException("minio down"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("DOCUMENT_STORAGE_UNAVAILABLE");
	}

	@Test
	void reportsAnUnknownOrder() {
		ResponseEntity<ApiResponse<Void>> response = this.handler.handleOrderNotFound(new OrderNotFoundException(99L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("ORDER_NOT_FOUND");
		assertThat(response.getBody().error().message()).isEqualTo("Order 99 was not found");
	}

	/**
	 * The reverse lookup fails with the prescription's id rather than an order id,
	 * so the message has to name the id the customer actually asked with.
	 */
	@Test
	void reportsAPrescriptionThatHasNotBeenOrdered() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleOrderNotFound(new OrderNotFoundException("Prescription 12 has not been ordered yet"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("ORDER_NOT_FOUND");
		assertThat(response.getBody().error().message()).isEqualTo("Prescription 12 has not been ordered yet");
	}

	@Test
	void reportsAnOrderTypeThatDoesNotFitThePrescription() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleInvalidOrder(new InvalidOrderException("Some order values are not valid",
					Map.of("orderType", "a progressive lens needs a prescription with a near addition on both eyes")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("INVALID_ORDER");
		assertThat(response.getBody().error().fieldErrors()).containsKey("orderType");
	}

	/**
	 * A conflict, not a bad request: the order was well formed and lost the race
	 * with an identical one, so a 400 would invite a retry that can never work.
	 */
	@Test
	void reportsASecondOrderForOnePrescriptionAsAConflict() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleOrderAlreadyExists(new OrderAlreadyExistsException(12L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("ORDER_ALREADY_EXISTS");
		assertThat(response.getBody().error().fieldErrors()).containsKey("prescriptionId");
	}

	/**
	 * An order type the API cannot parse is the customer's typo, so it has to come
	 * back as a 400. Left unhandled it would be a 500, which says "retry" and would
	 * never help. The non-numeric id in the path reaches the same handler and is
	 * covered end to end by {@code OrderControllerTest}.
	 */
	@Test
	void reportsAnUnreadableOrderAsABadRequestRatherThanAServerFault() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleUnreadableBody(new HttpMessageNotReadableException("unknown order type", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(response.getBody().error().fieldErrors()).isNullOrEmpty();
	}

	/**
	 * A conflict, not a bad request: the request was well formed, the prescription
	 * simply has already been decided and reviewing is one-way, so no shape of
	 * request would succeed. A 400 would invite a retry that can never work.
	 */
	@Test
	void reportsAnAlreadyReviewedPrescriptionAsAConflict() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handlePrescriptionNotReviewable(new PrescriptionNotReviewableException(12L, "REJECTED"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("PRESCRIPTION_NOT_REVIEWABLE");
	}

	@Test
	void reportsAnAlreadyDecidedOrderAsAConflict() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleOrderNotReviewable(new OrderNotReviewableException(3L, "APPROVED"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("ORDER_NOT_REVIEWABLE");
	}

	/**
	 * The message names the prescription and its real status, so a client can send
	 * the reviewer to the prescription that still needs work rather than guessing
	 * from the code alone.
	 */
	@Test
	void namesThePrescriptionThatBlocksApproval() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handlePrescriptionNotVerified(new PrescriptionNotVerifiedException(12L, "PENDING_REVIEW"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("PRESCRIPTION_NOT_VERIFIED");
		assertThat(response.getBody().error().message()).contains("12").contains("PENDING_REVIEW");
	}

	/**
	 * A receive date on an order the shop has not accepted is a 409 rather than a
	 * 400: the body is fine, it just does not apply until the order is approved.
	 */
	@Test
	void reportsAnOrderThatCannotCarryAReceiveDate() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleOrderNotApproved(new OrderNotApprovedException(3L, "PENDING_REVIEW"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("ORDER_NOT_APPROVED");
		assertThat(response.getBody().error().message()).contains("3").contains("PENDING_REVIEW");
	}

	/**
	 * A production step the order cannot make is a 409 rather than a 400: the request
	 * is fine, it just does not apply to an order in this state. The message names
	 * both ends of the refused move so a client knows which step to retry.
	 */
	@Test
	void reportsAnOrderThatCannotMakeTheRequestedStep() {
		ResponseEntity<ApiResponse<Void>> response = this.handler
			.handleOrderNotAdvancable(new OrderNotAdvancableException(3L, "DISPATCHED", OrderStatus.READY));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("ORDER_NOT_ADVANCABLE");
		assertThat(response.getBody().error().message()).contains("3").contains("DISPATCHED").contains("READY");
	}

	private static FieldError fieldError(String field, String message) {
		return new FieldError("prescription", field, null, false, null, null, message);
	}

}
