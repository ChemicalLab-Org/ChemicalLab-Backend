package com.morales.chemicallab.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The installer runs with writers stopped. A process keeps the contract it started with. */
@Component
@RequiredArgsConstructor
public class WhiteboardRuntimeContract {
    private final JdbcTemplate jdbc;
    private volatile Boolean installed;

    public boolean installed() {
        Boolean state = installed;
        if (state != null) return state;
        state = Boolean.TRUE.equals(jdbc.queryForObject("select to_regnamespace('board_runtime') is not null", Boolean.class));
        if (state) {
            jdbc.queryForObject("select version from evaluation_runtime.contract where version=1", Integer.class);
            jdbc.queryForObject("select version from content_runtime.contract where version=1", Integer.class);
            jdbc.queryForObject("select version from board_runtime.contract where version=1", Integer.class);
        }
        installed = state;
        return state;
    }
}
