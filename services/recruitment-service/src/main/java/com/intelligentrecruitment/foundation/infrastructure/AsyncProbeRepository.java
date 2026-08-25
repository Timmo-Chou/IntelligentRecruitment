package com.intelligentrecruitment.foundation.infrastructure;

import com.intelligentrecruitment.foundation.domain.AsyncProbe;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AsyncProbeRepository extends JpaRepository<AsyncProbe, UUID> {
}

