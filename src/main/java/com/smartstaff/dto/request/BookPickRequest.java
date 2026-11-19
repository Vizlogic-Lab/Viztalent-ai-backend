package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Picks one question from the company question book to copy into a paper. */
public record BookPickRequest(@NotNull UUID book_item_id) {}
