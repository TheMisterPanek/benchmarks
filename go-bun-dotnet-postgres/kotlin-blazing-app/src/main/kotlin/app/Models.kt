package app

import java.time.OffsetDateTime

data class CreateUserRequest(
    val name: String?,
    val address: String?,
    val phone: String?,
)

data class User(
    val id: Int,
    val name: String,
    val address: String,
    val phone: String,
    val image: String,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
)
