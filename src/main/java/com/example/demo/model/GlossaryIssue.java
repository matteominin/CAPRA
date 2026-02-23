package com.example.demo.model;

/**
 * Una incoerenza terminologica rilevata nel documento.
 *
 * @param termGroup    Il concetto/entità a cui si riferiscono i termini (es. "Utente del sistema")
 * @param variants     I diversi termini usati (es. "Utente, User, Cliente, Socio")
 * @param occurrences  Numero approssimativo di occorrenze delle varianti
 * @param suggestion   Suggerimento su quale termine usare in modo uniforme
 * @param severity     MAJOR se crea confusione reale, MINOR se solo stilistico
 */
public record GlossaryIssue(
        String termGroup,
        String variants,
        int occurrences,
        String suggestion,
        String severity
) {}
