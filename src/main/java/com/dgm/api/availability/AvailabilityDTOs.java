package com.dgm.api.availability;

// ── DTOs ─────────────────────────────────────────────────────────────────────

record TimeSlotDTO(String startTime, String endTime) {}

record ConflictResultDTO(boolean conflict, String conflictingWoId) {}

record AvailabilitySlotsRequest(String date, Integer durationMins) {}

record ConflictCheckRequest(String date, String startTime, Integer durationMins) {}
