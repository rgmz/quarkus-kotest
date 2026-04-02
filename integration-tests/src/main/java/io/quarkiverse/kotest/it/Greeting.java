package io.quarkiverse.kotest.it;

import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class Greeting extends PanacheEntity {

    public String message;
}
