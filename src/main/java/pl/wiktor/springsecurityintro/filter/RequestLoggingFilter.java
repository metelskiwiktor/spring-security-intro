package pl.wiktor.springsecurityintro.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            logRequestDetails(httpRequest);
        }

        chain.doFilter(request, response);
    }

    private void logRequestDetails(HttpServletRequest request) {
        logger.info("=== REQUEST DETAILS ===");
        logger.info("Method: {}", request.getMethod());
        logger.info("URL: {}", request.getRequestURL());
        logger.info("URI: {}", request.getRequestURI());
        logger.info("Query String: {}", request.getQueryString());
        logger.info("Remote Address: {}", request.getRemoteAddr());
        logger.info("Remote Host: {}", request.getRemoteHost());
        logger.info("Remote Port: {}", request.getRemotePort());
        logger.info("Protocol: {}", request.getProtocol());
        logger.info("Scheme: {}", request.getScheme());
        logger.info("Server Name: {}", request.getServerName());
        logger.info("Server Port: {}", request.getServerPort());
        logger.info("Context Path: {}", request.getContextPath());
        logger.info("Servlet Path: {}", request.getServletPath());
        logger.info("Path Info: {}", request.getPathInfo());

        logger.info("--- Headers ---");
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            logger.info("{}: {}", headerName, request.getHeader(headerName));
        });

        logger.info("--- Parameters ---");
        request.getParameterMap().forEach((paramName, paramValues) -> {
            logger.info("{}: {}", paramName, String.join(", ", paramValues));
        });

        logger.info("========================");
    }
}