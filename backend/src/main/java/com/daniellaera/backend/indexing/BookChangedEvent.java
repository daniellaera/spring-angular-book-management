package com.daniellaera.backend.indexing;

/** Published by the book service after a book was created, updated or deleted. */
public record BookChangedEvent(Integer bookId, Type type) {
    public enum Type { CREATED, UPDATED, DELETED }
}
