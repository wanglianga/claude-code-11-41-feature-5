package com.scf.meta.explain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayItemRepository extends JpaRepository<ReplayItem, String> {
    List<ReplayItem> findByReplayTaskId(String replayTaskId);
}
