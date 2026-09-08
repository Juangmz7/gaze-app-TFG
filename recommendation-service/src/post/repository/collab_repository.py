from uuid import UUID

from post.model.collab import Collab


class CollabRepository:
    def get(self, collab_id: UUID) -> Collab | None:
        pass

    def save(self, collab: Collab) -> None:
        pass

    def delete(self, collab_id: UUID) -> None:
        pass

    def find_post_id_by_collab_id(self, collab_id: UUID) -> UUID | None:
        pass

