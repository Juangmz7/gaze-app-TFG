package com.app.postcommandservice.commentlike.application.repository;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;

public interface PostCommentLikeCommandPublisher {

    void publish(ValidateCommentLikeCommand command);

    void publish(ValidateCommentUnlikeCommand command);
}
