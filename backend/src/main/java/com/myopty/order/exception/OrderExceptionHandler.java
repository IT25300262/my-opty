package com.myopty.order.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import com.myopty.order.dto.ApiResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps Order &amp; Prescription failures onto the standard error envelope.
 *
 * <p>Scoped to this module's exceptions so it cannot change how the other
 * modules report errors. A global handler belongs in {@code com.myopty.shared}.
 */
@RestControllerAdvice(basePackages = "com.myopty.order.controller")
public class OrderExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(OrderExceptionHandler.class);

	@ExceptionHandler(PrescriptionNotFoundException.class)
	ResponseEntity<ApiResponse<Void>> handleNotFound(PrescriptionNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error("PRESCRIPTION_NOT_FOUND", ex.getMessage()));
	}

	/**
	 * The prescription is there, the document is not. Reporting this as a storage
	 * failure would tell the client to retry something that will never succeed.
	 */
	@ExceptionHandler(PrescriptionDocumentNotFoundException.class)
	ResponseEntity<ApiResponse<Void>> handleDocumentNotFound(PrescriptionDocumentNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error("DOCUMENT_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(InvalidPrescriptionException.class)
	ResponseEntity<ApiResponse<Void>> handleInvalidPrescription(InvalidPrescriptionException ex) {
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("INVALID_PRESCRIPTION", ex.getMessage(), ex.getFieldErrors()));
	}

	@ExceptionHandler(InvalidPrescriptionDocumentException.class)
	ResponseEntity<ApiResponse<Void>> handleInvalidDocument(InvalidPrescriptionDocumentException ex) {
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("INVALID_DOCUMENT", ex.getMessage(), Map.of("document", ex.getMessage())));
	}

	@ExceptionHandler(OrderNotFoundException.class)
	ResponseEntity<ApiResponse<Void>> handleOrderNotFound(OrderNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error("ORDER_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(InvalidOrderException.class)
	ResponseEntity<ApiResponse<Void>> handleInvalidOrder(InvalidOrderException ex) {
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("INVALID_ORDER", ex.getMessage(), ex.getFieldErrors()));
	}

	/**
	 * Not a bad request: the order was well formed, it just collides with one that
	 * already exists, so sending the same body again can never succeed.
	 */
	@ExceptionHandler(OrderAlreadyExistsException.class)
	ResponseEntity<ApiResponse<Void>> handleOrderAlreadyExists(OrderAlreadyExistsException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error("ORDER_ALREADY_EXISTS", ex.getMessage(), Map.of("prescriptionId", ex.getMessage())));
	}

	/**
	 * The prescription exists and the review is a real transition, but it has
	 * already been decided. 409 rather than 400 because no shape of request would
	 * succeed here: reviewing is one-way by design.
	 */
	@ExceptionHandler(PrescriptionNotReviewableException.class)
	ResponseEntity<ApiResponse<Void>> handlePrescriptionNotReviewable(PrescriptionNotReviewableException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error("PRESCRIPTION_NOT_REVIEWABLE", ex.getMessage()));
	}

	/**
	 * Same one-way reasoning as the prescription above, applied to the client's
	 * approve or reject call on an order.
	 */
	@ExceptionHandler(OrderNotReviewableException.class)
	ResponseEntity<ApiResponse<Void>> handleOrderNotReviewable(OrderNotReviewableException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error("ORDER_NOT_REVIEWABLE", ex.getMessage()));
	}

	/**
	 * Both rows are fine on their own; what conflicts is the pair, because an
	 * unverified prescription must never reach production. The message names the
	 * prescription and its real status so the client knows exactly what to go and
	 * review first.
	 */
	@ExceptionHandler(PrescriptionNotVerifiedException.class)
	ResponseEntity<ApiResponse<Void>> handlePrescriptionNotVerified(PrescriptionNotVerifiedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error("PRESCRIPTION_NOT_VERIFIED", ex.getMessage()));
	}

	/**
	 * The order is real and the date is well formed, but this order has not been
	 * accepted yet, so it has no receive date to set. 409 because the same body
	 * would work once the shop approves the order.
	 */
	@ExceptionHandler(OrderNotApprovedException.class)
	ResponseEntity<ApiResponse<Void>> handleOrderNotApproved(OrderNotApprovedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error("ORDER_NOT_APPROVED", ex.getMessage()));
	}

	/**
	 * The order is real and the step asked for is a real one, but this order cannot
	 * make that move: a rejected order never enters production, and a dispatched one
	 * has nothing left to do. 409 because the same request would work on an order in
	 * the right state.
	 */
	@ExceptionHandler(OrderNotAdvancableException.class)
	ResponseEntity<ApiResponse<Void>> handleOrderNotAdvancable(OrderNotAdvancableException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error("ORDER_NOT_ADVANCABLE", ex.getMessage()));
	}

	/**
	 * A body the JSON reader cannot turn into the request, which for this module
	 * most often means an order type spelled wrongly. Reporting it as a bad
	 * request is the point: without this the customer would see a 500 for a
	 * typo, and a 500 says retry, which would never help.
	 */
	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<ApiResponse<Void>> handleUnreadableBody(Exception ex) {
		logger.debug("Rejected an unreadable order request", ex);
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("MALFORMED_REQUEST", "The order request could not be read", null));
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
		String message = "The " + ex.getRequestPartName() + " part is required";
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("MISSING_PART", message, Map.of(ex.getRequestPartName(), "is required")));
	}

	/**
	 * Bean validation on the {@code prescription} part. {@link BindException} also
	 * covers {@code MethodArgumentNotValidException}, which is what Spring MVC
	 * raises for a request part.
	 */
	@ExceptionHandler(BindException.class)
	ResponseEntity<ApiResponse<Void>> handleValidation(BindException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.merge(error.getField(), error.getDefaultMessage(),
					(existing, next) -> existing + "; " + next);
		}
		for (var error : ex.getBindingResult().getGlobalErrors()) {
			fieldErrors.putIfAbsent(error.getObjectName(), error.getDefaultMessage());
		}
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("VALIDATION_FAILED", "Some prescription values are not valid", fieldErrors));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
			.body(ApiResponse.error("DOCUMENT_TOO_LARGE", "The prescription document is too large", null));
	}

	@ExceptionHandler({ MultipartException.class, MissingServletRequestParameterException.class })
	ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception ex) {
		logger.debug("Rejected malformed prescription request", ex);
		return ResponseEntity.badRequest()
			.body(ApiResponse.error("MALFORMED_REQUEST", "The prescription request could not be read", null));
	}

	/**
	 * Object storage is a downstream dependency, so its failure is reported as a
	 * bad gateway: the customer can retry without re-entering anything.
	 */
	@ExceptionHandler(DocumentStorageException.class)
	ResponseEntity<ApiResponse<Void>> handleStorageFailure(DocumentStorageException ex) {
		logger.error("Prescription document storage is unavailable", ex);
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error("DOCUMENT_STORAGE_UNAVAILABLE",
				"The prescription document could not be stored right now. Please try again.", null));
	}

}
