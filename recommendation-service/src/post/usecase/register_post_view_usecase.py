
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from post.command.post_commands import RegisterPostViewCommand


class RegisterPostViewUsecase:
    def __init__(
            self,
            user_creator_features_repostory: UserCreatorFeaturesRepository,
            post_tags_features_repository: PostTagFeaturesRepository,
            post_features_repository: PostFeaturesRepository
    ):
        self.user_creator_features_repostory = user_creator_features_repostory
        self.post_tags_features_repository = post_tags_features_repository
        self.post_features_repository = post_features_repository

    ## TODO:
    def execute(self, command: RegisterPostViewCommand) -> None:
        user_creator_features = self.user_creator_features_repostory.get_user_creator_features(
            command.post_id, command.user_id
        )

        post_features = self.post_features_repository.get_post_features(command.post_id)
        post_tags_features = self.post_tags_features_repository.getPostsTagsFeatures(
            command.user_id, 
            post_features.tags
        )

        ## Make two different lists with the raw and decayed metrics

        ## Update common metrics raw and decayed
        
        if command.completion_percent < 5:
            ## penalizacion en el embedding y ++ en fastskip
            self._process_fast_skip(raw, decayed)

        if command.completion_percent < 95:
            exit_reason_weight = self._get_exit_reason_weight()
            self._process_incomplete_view()
            
        else:
            self._process_complete_view()

        ## Update de afinidad

        ## Update del user embedding
