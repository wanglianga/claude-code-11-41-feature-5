package com.scf.meta.explain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayTaskRepository extends JpaRepository<ReplayTask, String> {
    List<ReplayTask> findByBuildIdOrderByCreatedAtDesc(String buildId);
    List<ReplayTask> findAllByOrderByCreatedAtDesc();
}
