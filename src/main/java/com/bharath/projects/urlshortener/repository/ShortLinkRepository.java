package com.bharath.projects.urlshortener.repository;

import com.bharath.projects.urlshortener.entity.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    Optional<ShortLink> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Atomic in-database increment: safe under concurrent clicks (no read-modify-write).
     *
     * @return number of rows updated (0 if the link no longer exists)
     */
    @Transactional
    @Modifying
    @Query("update ShortLink s set s.clickCount = s.clickCount + 1 where s.id = :id")
    int incrementClickCount(@Param("id") Long id);
}
