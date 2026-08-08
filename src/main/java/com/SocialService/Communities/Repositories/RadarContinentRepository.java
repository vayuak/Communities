package com.SocialService.Communities.Repositories;

import com.SocialService.Communities.Models.RadarContinent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RadarContinentRepository extends JpaRepository<RadarContinent, Long> {
}