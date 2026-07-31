package com.edutwin.provenance;

import com.edutwin.api.TransparencyApi;
import com.edutwin.api.model.TransparencyResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

public class TransparencyController implements TransparencyApi {

    private final TransparencyReadService service;

    public TransparencyController(TransparencyReadService service) {
        this.service = service;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TransparencyResponse> getTransparency() {
        return ResponseEntity.ok(service.get());
    }
}
