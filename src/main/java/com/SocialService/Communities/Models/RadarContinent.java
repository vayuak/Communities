package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "radar_continents")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class RadarContinent implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name; // e.g., "EUROPE", "SOUTH ASIA"

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "radar_cities", joinColumns = @JoinColumn(name = "continent_id"))
    @Column(name = "city_name")
    @Builder.Default
    private Set<String> cities = new HashSet<>();
}