package com.example.demo.model;

/**
 * Una singola riga della matrice di tracciabilità: un Use Case / Requisito con i relativi link.
 *
 * @param useCaseId     Identificativo del caso d'uso (es. UC-1, UC-2) o requisito
 * @param useCaseName   Nome del caso d'uso / requisito
 * @param hasDesign     Se esiste una descrizione di design/architettura correlata
 * @param hasTest       Se esiste un test case correlato
 * @param designRef     Breve riferimento al design (es. "Classe ReservationController")
 * @param testRef       Breve riferimento al test (es. "testReserve_Success")
 * @param gap           Descrizione del gap, se presente. Vuoto se tutto coperto.
 */
public record TraceabilityEntry(
        String useCaseId,
        String useCaseName,
        boolean hasDesign,
        boolean hasTest,
        String designRef,
        String testRef,
        String gap
) {}
