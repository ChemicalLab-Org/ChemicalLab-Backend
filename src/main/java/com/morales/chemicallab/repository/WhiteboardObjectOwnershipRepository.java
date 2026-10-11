package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.WhiteboardObjectOwnership;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WhiteboardObjectOwnershipRepository extends JpaRepository<WhiteboardObjectOwnership, Long> {
    List<WhiteboardObjectOwnership> findByBoard_Id(Long boardId);
}
