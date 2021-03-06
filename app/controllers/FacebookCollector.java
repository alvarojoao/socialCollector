package controllers;

import org.joda.time.DateTime;
import org.springframework.social.facebook.api.Comment;
import org.springframework.social.facebook.api.Facebook;
import org.springframework.social.facebook.api.PagedList;
import org.springframework.social.facebook.api.PagingParameters;
import org.springframework.social.facebook.api.Post;
import org.springframework.social.facebook.api.Reference;
import org.springframework.social.facebook.api.impl.FacebookTemplate;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

import bootstrap.CollectorInfo;
import bootstrap.DS;
import models.Page;
import models.User;
import play.Logger;
import play.Play;
import service.MongoService;

/**
 * Created by alvaro.joao.silvino on 20/08/2015.
 */
//db.user.find({$where:'this.comments.length>4'}).pretty()
//> db.user.find({$and:[{$where:'this.comments.length>4'},{$where:'this.likes.length>4'}]}).pretty()
public class FacebookCollector {


    public static String token = Play.application().configuration().getString("facebook.token");
    public static Facebook facebook = new FacebookTemplate(token);
    public static boolean updateAllPost = Play.application().configuration().getBoolean("updateAllPost");;

    public static void collect(CollectorInfo.Moment moment){
        List<Page> pages = MongoService.getAllPagesFacebook();
        // Collections.shuffle(pages);

        for(Page page:pages) {

            try {

                long lastMonth = DateTime.now().minusMonths(1).toDateTime().getMillis()/1000;
                long now = DateTime.now().toDateTime().getMillis()/1000;



                PagingParameters pagingParameters = new PagingParameters(25,null,lastMonth,now);
                PagedList<Post> posts ;
                switch (moment){
                    case ALL:
                        posts = facebook.feedOperations().getPosts(page.getId());
                        break;
                    case RECENT:
                        posts = facebook.feedOperations().getPosts(page.getId(),pagingParameters);
                        break;
                    default:
                        posts = facebook.feedOperations().getPosts(page.getId(),pagingParameters);
                        break;
                }

                boolean firstTime = true;
                Logger.debug("Started:"+page.getTitle()+"  " );
                try{
                    do{
                        if(!firstTime){
                            try{
                                posts = facebook.feedOperations().getPosts(page.getId(),posts.getNextPage());
                            }catch (Exception e){
                                Logger.debug("FacebookCollector - error on get more  posts: "+e.getMessage() +" - Trying again.");
                                posts = facebook.feedOperations().getPosts(page.getId(),posts.getNextPage());

                            }
                        }
                        firstTime = false;
                        for(Post post: posts) {
                            try{
                                if(!MongoService.existsPost(post.getId())||updateAllPost){
                                    Logger.debug("FacebookCollector -  post - not in base + updateAllPost:"+ updateAllPost + "");

                                    Set<User> users = new HashSet<>();
                                    Set<Comment> comments = new HashSet<>();
                                    
                                    try{

                                        fetchCommentAndUpdateUsers(post, comments, users, page);

                                    }catch (Exception e2){
                                        Logger.debug("FacebookCollector -  error on get Comments: "+e2.getMessage() +" - not Trying again.");
                                        // try{
                                        //     fetchCommentAndUpdateUsers(post, comments, users, page);
                                        // }catch (Exception e3){
                                        //     Logger.debug("FacebookCollector -  error on get Comments : "+e3.getMessage() +" - Continue to next post.");
                                        // }
                                    }

                                    try{

                                        fetchLikesAndUpdateUsers(post, users,page);

                                    }catch (Exception e){
                                        Logger.debug("FacebookCollector - error on get Likes: "+e.getMessage() +" - not Trying again.");
                                        // try{
                                        //     fetchLikesAndUpdateUsers(post, users,page);
                                        // }catch (Exception e1){
                                        //     Logger.debug("FacebookCollector - error on get Likes: "+e1.getMessage() +" - Continue to Comments.");
                                        // }
                                    }

                                    
                                    MongoService.save(post);

                                    for(Comment comment: comments){
                                        MongoService.save(comment,page,post.getId());
                                    }
                                    //save or update users iterations
                                    MongoService.save(users);
                                }else{
                                    Logger.debug("FacebookCollector - post - already in base + updateAllPost:"+ updateAllPost + "");
                                }
                            }catch (Exception e){
                                Logger.debug("FacebookCollector - error , going to next post: "+e.getMessage());
                            }
                        }
                        Logger.debug("update:"+page.getTitle()+"  " );
                    }while(posts.getNextPage()!=null);
                }catch (Exception e){
                    Logger.debug("error , going to next page: "+e.getMessage());
                }
                Logger.debug("Finished page:"+page.getTitle());

            }catch (Exception e){
                Logger.debug("error on page:"+page.getTitle());
                Logger.error("error on page:"+e.getMessage());
                Logger.error("Token: "+token);
            }
        }
        Logger.debug("Finished FACEBOOK Collect on MODE:"+moment.name()+"  " );

    }

    private static void fetchLikesAndUpdateUsers(Post post, Set<User> users,Page page) {
        PagedList<Reference> likes = facebook.likeOperations().getLikes(post.getId());
        int totalLikes = likes.size();
        for(Reference userLike: likes) {
            User user = users.stream().filter(f->f.getId().equals(userLike.getId())).findFirst().orElse(new User(userLike.getId(),userLike.getName(),page));
            user.addLike(post,page.getId());
            users.add(user);
        }
        while (likes.getNextPage() != null) {
            likes = facebook.likeOperations().getLikes(post.getId(), likes.getNextPage());
            totalLikes +=likes.size();
            for(Reference userLike: likes) {
                User user = users.stream().filter(f->f.getId().equals(userLike.getId())).findFirst().orElse(new User(userLike.getId(),userLike.getName(),page));
                user.addLike(post,page.getId());
                users.add(user);
                if(users.size()>1000){
                    MongoService.save(users);
                    users = new HashSet<>();
                }
            }
        }
        post.getExtraData().putIfAbsent("likesCount",totalLikes);
    }

    private static void fetchCommentAndUpdateUsers(Post post,Set<Comment> commentsToSave, Set<User> users,Page page) {
        PagedList<Comment> comments = facebook.commentOperations().getComments(post.getId());
        int totalComments = comments.size();

        for(Comment comment: comments) {

            User user = users.stream().filter(f->f.getId().equals(comment.getFrom().getId())).findFirst().orElse(new User(comment.getFrom().getId(),comment.getFrom().getName(),page));
            user.addComment(comment, post,page.getId());
            users.add(user);


            commentsToSave.add(comment);
            if(comment.getCommentCount()!=null&&comment.getCommentCount()>0){
                PagedList<Comment> commentsComments = facebook.commentOperations().getComments(comment.getId());
                for(Comment commentsComment: commentsComments) {

                    user = users.stream().filter(f->f.getId().equals(commentsComment.getFrom().getId())).findFirst().orElse(new User(comment.getFrom().getId(),comment.getFrom().getName(),page));
                    user.addComment(commentsComment, post,page.getId());
                    users.add(user);
                    commentsToSave.add(comment);
                }
                while (commentsComments.getNextPage() != null) {
                    commentsComments = facebook.commentOperations().getComments(post.getId(), comments.getNextPage());
                    for(Comment commentsComment: commentsComments) {
                        user = users.stream().filter(f->f.getId().equals(commentsComment.getFrom().getId())).findFirst().orElse(new User(comment.getFrom().getId(),comment.getFrom().getName(),page));
                        user.addComment(commentsComment, post,page.getId());
                        users.add(user);
                        commentsToSave.add(comment);
                    }
                }

            }
        }

        while (comments.getNextPage() != null) {
            comments = facebook.commentOperations().getComments(post.getId(), comments.getNextPage());
            totalComments +=comments.size();

            for(Comment comment: comments) {
                User user = users.stream().filter(f->f.getId().equals(comment.getFrom().getId())).findFirst().orElse(new User(comment.getFrom().getId(),comment.getFrom().getName(),page));
                user.addComment(comment, post,page.getId());
                users.add(user);
                commentsToSave.add(comment);
                if(comment.getCommentCount()!=null&&comment.getCommentCount()>0){
                    PagedList<Comment> commentsComments = facebook.commentOperations().getComments(comment.getId());
                    for(Comment commentsComment: commentsComments) {

                        user = users.stream().filter(f->f.getId().equals(commentsComment.getFrom().getId())).findFirst().orElse(new User(comment.getFrom().getId(),comment.getFrom().getName(),page));
                        user.addComment(commentsComment, post,page.getId());
                        users.add(user);
                        commentsToSave.add(comment);
                    }
                    while (commentsComments.getNextPage() != null) {
                        commentsComments = facebook.commentOperations().getComments(post.getId(), comments.getNextPage());
                        for(Comment commentsComment: commentsComments) {
                            user = users.stream().filter(f->f.getId().equals(commentsComment.getFrom().getId())).findFirst().orElse(new User(comment.getFrom().getId(),comment.getFrom().getName(),page));
                            user.addComment(commentsComment, post,page.getId());
                            users.add(user);
                            commentsToSave.add(comment);
                        }
                    }
                }
            }

            if(commentsToSave.size()>100){
                for(Comment comment: commentsToSave){
                    MongoService.save(comment,page,post.getId());
                }
                commentsToSave = new HashSet<>();
            }

        }
        post.getExtraData().putIfAbsent("commentsCount",totalComments);

    }

}
