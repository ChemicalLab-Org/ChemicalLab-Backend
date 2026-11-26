package com.morales.chemicallab.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdministrationRuntimeContract {
    private final JdbcTemplate jdbc;
    private volatile Boolean installed;

    public boolean installed() {
        Boolean state = installed;
        if (state != null) return state;
        state = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regnamespace('administration_runtime') is not null", Boolean.class));
        if (state) jdbc.queryForObject("select version from administration_runtime.contract where version=1", Integer.class);
        // An installation or upgrade always restarts the application.
        installed = state;
        return state;
    }
}
