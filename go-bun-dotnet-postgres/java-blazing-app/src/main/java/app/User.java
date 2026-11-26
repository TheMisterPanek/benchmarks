package app;

import java.time.OffsetDateTime;

public record User(
    int id,
    String name,
    String address,
    String phone,
    String image,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
