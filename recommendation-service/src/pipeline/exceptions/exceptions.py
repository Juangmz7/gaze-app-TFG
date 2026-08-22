from uuid import UUID


class DontRequeuePipelineException(Exception):
    """Base exception for pipeline use case failures."""


class UserCreatorFeaturesNotFoundException(DontRequeuePipelineException):
    def __init__(self, post_id: UUID, user_id: UUID):
        super().__init__(
            f"User creator features were not found for post_id={post_id} and user_id={user_id}"
        )


class PostFeaturesNotFoundException(DontRequeuePipelineException):
    def __init__(self, post_id: UUID):
        super().__init__(f"Post features were not found for post_id={post_id}")


class PostTagFeaturesNotFoundException(DontRequeuePipelineException):
    def __init__(self, post_id: UUID):
        super().__init__(f"Post tag features were not found for post_id={post_id}")
