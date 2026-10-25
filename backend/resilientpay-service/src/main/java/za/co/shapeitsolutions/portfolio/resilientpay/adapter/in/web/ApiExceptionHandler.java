package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.CorrelationIdFilter;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;

/**
 * Converts validation, application, and domain failures to stable redacted Problem Details.
 *
 * @author Daniel Jr Nkulu
 */
@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Keeps absent profile-gated endpoints absent instead of turning MVC's 404 into a server error. */
    @ExceptionHandler({
        org.springframework.web.servlet.NoHandlerFoundException.class,
        org.springframework.web.servlet.resource.NoResourceFoundException.class
    })
    ResponseEntity<ProblemResponse> absentResource(Exception exception, HttpServletRequest request) {
        return problem(
                ApplicationErrorCode.RESOURCE_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "The requested resource was not found",
                request,
                null);
    }

    /** Maps stable application errors without exposing tenant or infrastructure internals. */
    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemResponse> application(ApplicationException exception, HttpServletRequest request) {
        return problem(exception.code(), status(exception.code()), exception.getMessage(), request, null);
    }

    /** Maps framework-free domain errors to the public application vocabulary. */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemResponse> domain(DomainException exception, HttpServletRequest request) {
        ApplicationErrorCode code =
                switch (exception.code()) {
                    case MONEY_MISMATCH -> ApplicationErrorCode.REVERSAL_NOT_ELIGIBLE;
                    case POLICY_NOT_ACTIVE -> ApplicationErrorCode.POLICY_NOT_ACTIVE;
                    case TENANT_SCOPE_MISMATCH -> ApplicationErrorCode.RESOURCE_NOT_FOUND;
                    case RECONCILIATION_APPROVER_CONFLICT, POLICY_APPROVER_CONFLICT ->
                        ApplicationErrorCode.RECONCILIATION_APPROVER_CONFLICT;
                    case STATE_TRANSITION_NOT_ALLOWED, RETRY_NOT_PROVEN_SAFE, ATTEMPT_LIMIT_EXCEEDED ->
                        ApplicationErrorCode.STATE_TRANSITION_NOT_ALLOWED;
                    case INVALID_VALUE -> ApplicationErrorCode.INVALID_REQUEST;
                };
        return problem(code, status(code), exception.getMessage(), request, null);
    }

    /** Maps unavailable database connections to the published fail-closed 503 contract. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemResponse> dependencyUnavailable(Exception exception, HttpServletRequest request) {
        LOGGER.warn(
                "Request dependency unavailable; correlationId={}, type={}",
                correlationId(),
                exception.getClass().getName());
        return problem(
                ApplicationErrorCode.DEPENDENCY_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                "A required service is temporarily unavailable",
                request,
                null);
    }

    /** Returns field-specific validation errors for malformed JSON values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemResponse> beanValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.computeIfAbsent(fieldError.getField(), ignored -> new ArrayList<>())
                    .add(fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage());
        }
        return problem(
                ApplicationErrorCode.INVALID_REQUEST,
                HttpStatus.BAD_REQUEST,
                "One or more request fields are invalid",
                request,
                errors);
    }

    /** Maps query/path validation to a bounded invalid-request problem. */
    @ExceptionHandler({
        ConstraintViolationException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ProblemResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        return problem(
                ApplicationErrorCode.INVALID_REQUEST,
                HttpStatus.BAD_REQUEST,
                "The request syntax, header, path, or value is invalid",
                request,
                null);
    }

    /** Fails closed with a redacted server problem and a correlation-only log reference. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemResponse> unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
                "Unhandled request failure; correlationId={}, type={}",
                correlationId(),
                exception.getClass().getName());
        ProblemResponse body = new ProblemResponse(
                "urn:resilientpay:problem:internal-error",
                "Internal service error",
                500,
                "The request could not be completed",
                request.getRequestURI(),
                ApplicationErrorCode.INTERNAL_ERROR.name(),
                correlationId(),
                null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static ResponseEntity<ProblemResponse> problem(
            ApplicationErrorCode code,
            HttpStatus status,
            String detail,
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors) {
        String slug = code.name().toLowerCase(Locale.ROOT).replace('_', '-');
        ProblemResponse body = new ProblemResponse(
                "urn:resilientpay:problem:" + slug,
                title(code),
                status.value(),
                detail,
                request.getRequestURI(),
                code.name(),
                correlationId(),
                fieldErrors);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static HttpStatus status(ApplicationErrorCode code) {
        return switch (code) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case RESOURCE_NOT_FOUND, TENANT_SCOPE_MISMATCH -> HttpStatus.NOT_FOUND;
            case REVERSAL_NOT_ELIGIBLE, POLICY_NOT_ACTIVE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case REVERSAL_ALREADY_EXISTS,
                    IDEMPOTENCY_KEY_REUSED,
                    STATE_TRANSITION_NOT_ALLOWED,
                    RECONCILIATION_APPROVER_CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String title(ApplicationErrorCode code) {
        String normalised = code.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalised.charAt(0)) + normalised.substring(1);
    }

    private static String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
