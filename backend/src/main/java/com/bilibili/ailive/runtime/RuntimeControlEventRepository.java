package com.bilibili.ailive.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface RuntimeControlEventRepository extends JpaRepository<RuntimeControlEvent, UUID> {

    Optional<RuntimeControlEvent> findFirstByOrderByCreatedAtDesc();
}
