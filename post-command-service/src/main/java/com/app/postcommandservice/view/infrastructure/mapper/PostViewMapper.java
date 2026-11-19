package com.app.postcommandservice.view.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.view.domain.model.PostView;
import com.app.postcommandservice.view.infrastructure.entity.PostViewEntity;

@Component
public class PostViewMapper {

    public PostView toDomain(PostViewEntity entity) {
        return new PostView(
                entity.getId(),
                new PostId(entity.getPostId()),
                new UserId(entity.getUserId()),
                entity.getSource(),
                entity.getFeedPosition(),
                entity.getDurationMs(),
                entity.getTimeWatchedMs(),
                entity.getCompletionPercent(),
                entity.getExitReason(),
                entity.getServerTimestamp(),
                entity.getReplayCount()
        );
    }
}
