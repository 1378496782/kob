package com.kob.backend.consumer;

import com.alibaba.fastjson.JSONObject;
import com.kob.backend.consumer.utils.Game;
import com.kob.backend.consumer.utils.JwtAuthentication;
import com.kob.backend.mapper.UserMapper;
import com.kob.backend.pojo.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@ServerEndpoint("/websocket/{token}")  // WebSocket服务器端点，注意不要以'/'结尾
public class WebSocketServer {
    // 用于存储所有连接的WebSocketServer实例，每个用户对应一个实例
    private final static ConcurrentHashMap<Integer, WebSocketServer> users = new ConcurrentHashMap<Integer, WebSocketServer>();
    private final static CopyOnWriteArraySet<User> matchpool = new CopyOnWriteArraySet<>();

    // 当前WebSocket连接的用户
    private User user;

    // 当前WebSocket的会话
    private Session session = null;

    // 静态的UserMapper对象，用于从数据库中获取用户信息
    private static UserMapper userMapper;

    // 通过@Autowired注入UserMapper，并赋值给静态变量
    @Autowired
    public void setUserMapper(UserMapper userMapper) {
        WebSocketServer.userMapper = userMapper;
    }

    // 当有新连接建立时调用
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) throws IOException {
        this.session = session;  // 将当前会话赋值给实例变量
        System.out.println("connected to websocket");  // 输出连接成功的信息

        // 从路径参数中获取用户ID并转换为Integer
        int userId = JwtAuthentication.getUserId(token);

        // 从数据库中查询用户信息并赋值给当前实例的user变量
        user = userMapper.selectById(userId);

        // 将当前用户和WebSocketServer实例添加到用户集合中
        if (user != null) {
//            System.out.println("token: " + token);
            users.put(userId, this);
            System.out.println(user + " connected!");
        } else {
            session.close();
        }

//        System.out.println("Users: " + users);
    }

    // 当连接关闭时调用
    @OnClose
    public void onClose() {
        System.out.println("disconnected from websocket");  // 输出断开连接的信息

        // 如果当前用户不为空，从用户集合中移除该用户
        if (user != null) {
            users.remove(user.getId());
            matchpool.remove(user);
        }
    }

    private void startMatching() {
        System.out.println("start matching");
        matchpool.add(user);

        while (matchpool.size() > 1) {
            Iterator<User> it = matchpool.iterator();
            User a = it.next(), b = it.next();
            matchpool.remove(a);
            matchpool.remove(b);

            Game game = new Game(13, 14, 20);
            game.createMap();

            JSONObject respA = new JSONObject();
            respA.put("event", "start-matching");
            respA.put("opponent_username", b.getUsername());
            respA.put("opponent_photo", b.getPhoto());
            respA.put("gamemap", game.getG());
            users.get(a.getId()).sendMessage(respA.toJSONString());

            JSONObject respB = new JSONObject();
            respB.put("event", "start-matching");
            respB.put("opponent_username", a.getUsername());
            respB.put("opponent_photo", a.getPhoto());
            respB.put("gamemap", game.getG());
            users.get(b.getId()).sendMessage(respB.toJSONString());


        }
    }

    private void stopMatching() {
        System.out.println("stop matching");
        matchpool.remove(user);
    }

    // 当从客户端收到消息时调用
    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("received message: " + message);  // 输出接收到的消息
        JSONObject data = JSONObject.parseObject(message);
        String event = data.getString("event");
        if ("start-matching".equals(event)) {
            startMatching();
        } else {
            stopMatching();
        }
    }

    // 当发生错误时调用
    @OnError
    public void onError(Session session, Throwable error) {
        error.printStackTrace();  // 打印错误堆栈信息
    }

    // 发送消息给客户端
    public void sendMessage(String message) {
        // 同步块，确保在会话的多个线程之间发送消息时的线程安全
        synchronized (session) {
            session.getAsyncRemote().sendText(message);  // 异步发送消息给客户端
        }
    }
}