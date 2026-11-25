package com.morales.chemicallab.dto;

import java.util.List;
import java.util.UUID;

public final class InstitutionalContent {
    private InstitutionalContent() {}

    public record Scope(long institution, long clazz) {}
    public record Revision(long contentId, long revisionId) {}
    public record Choice(long institutionId, String institutionName, long classId, String className,
                         boolean student, boolean teacher, boolean canCreate) {}
    public record Row(long contentId, long revisionId, int number, long version, String status, String title,
                      String category, boolean canEdit, boolean canPublish, boolean canAssign, boolean assigned) {}
    public record Delivery(long deliveryId, long contentId, long revisionId, int number, String title, String category) {}
    public record Link(String title, String url) {}
    public record Text(String title, String category, String summary, String explanation, List<String> formationSteps,
                       List<String> keyPoints, List<String> examples, String suggestedActivity, List<Link> links) {
        public boolean hasBody() {
            return summary != null || explanation != null || suggestedActivity != null || !formationSteps.isEmpty()
                || !keyPoints.isEmpty() || !examples.isEmpty() || !links.isEmpty();
        }
    }
    public record File(long id, String name, String title, String mime, int size, boolean preview) {}
    public record Detail(long contentId, long revisionId, int number, long version, String status,
                         Text text, File file, int materialCount) {}
    public record Command(UUID operationId, Long version, Text text) {}
    public record Receipt(UUID operationId, long contentId, long revisionId, int number, long version,
                          String status, Long deliveryId, Integer recipients, File file) {}
}
