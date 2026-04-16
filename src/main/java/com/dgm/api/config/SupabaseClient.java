package com.dgm.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Supabase REST API (PostgREST).
 * All database reads and writes flow through this class.
 *
 * Swap this class out if you move from Supabase to another
 * Postgres host — nothing else in the codebase changes.
 */
@Slf4j
@Component
public class SupabaseClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-key}")
    private String serviceKey;

    // ── Generic helpers ──────────────────────────────────────────────────────

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("apikey", serviceKey);
        h.setBearerAuth(serviceKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Prefer", "return=representation");
        return h;
    }

    private String table(String tableName) {
        return supabaseUrl + "/rest/v1/" + tableName;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public Map<String, Object> findById(String tableName, String id) {
        String url = table(tableName) + "?id=eq." + id + "&limit=1";
        ResponseEntity<List<Map<String, Object>>> res = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        List<Map<String, Object>> body = res.getBody();
        if (body == null || body.isEmpty()) {
            throw new RuntimeException("Record not found in " + tableName + " with id=" + id);
        }
        return body.get(0);
    }

    public List<Map<String, Object>> findByColumn(
            String tableName, String column, String value) {
        String url = table(tableName) + "?" + column + "=eq." + value;
        ResponseEntity<List<Map<String, Object>>> res = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        return res.getBody();
    }

    public List<Map<String, Object>> findByColumns(
            String tableName, Map<String, String> filters) {
        StringBuilder query = new StringBuilder(table(tableName) + "?");
        filters.forEach((k, v) -> query.append(k).append("=eq.").append(v).append("&"));
        ResponseEntity<List<Map<String, Object>>> res = restTemplate.exchange(
                query.toString(), HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        return res.getBody();
    }

    // ── Write ────────────────────────────────────────────────────────────────

    public Map<String, Object> insert(String tableName, Map<String, Object> data) {
        ResponseEntity<List<Map<String, Object>>> res = restTemplate.exchange(
                table(tableName), HttpMethod.POST,
                new HttpEntity<>(data, headers()),
                new ParameterizedTypeReference<>() {}
        );
        List<Map<String, Object>> body = res.getBody();
        if (body == null || body.isEmpty()) {
            throw new RuntimeException("Insert to " + tableName + " returned no data");
        }
        return body.get(0);
    }

    public void update(String tableName, String id, Map<String, Object> data) {
        String url = table(tableName) + "?id=eq." + id;
        restTemplate.exchange(
                url, HttpMethod.PATCH,
                new HttpEntity<>(data, headers()),
                Void.class
        );
    }

    /**
     * Accepts a raw PostgREST query string for complex filters.
     * Example: "next_due_date=lte.2025-08-01&active=eq.true"
     * Used for filters that cannot be expressed as simple column=value pairs.
     */
    public List<Map<String, Object>> findByRawFilter(String tableName, String filter) {
        String url = table(tableName) + "?" + filter;
        ResponseEntity<List<Map<String, Object>>> res = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        return res.getBody();
    }

    public void deleteById(String tableName, String id) {
        String url = table(tableName) + "?id=eq." + id;
        restTemplate.exchange(
                url, HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                Void.class
        );
    }
}
