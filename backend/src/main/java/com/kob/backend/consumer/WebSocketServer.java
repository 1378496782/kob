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
@ServerEndpoint("/websocket/{token}")  // WebSocket服务器端点，客户端通过此地址连接
public class WebSocketServer {
    // 用于存储所有连接的WebSocketServer实例，每个用户对应一个实例
    public final static ConcurrentHashMap<Integer, WebSocketServer> users = new ConcurrentHashMap<>();

    // 用于存储所有正在匹配的用户
    private final static CopyOnWriteArraySet<User> matchpool = new CopyOnWriteArraySet<>();

    // 当前WebSocket连接对应的用户
    private User user;

    // 当前WebSocket的会话对象，用于与客户端通信
    private Session session = null;

    // 静态的UserMapper对象，用于从数据库中获取用户信息
    private static UserMapper userMapper;

    private Game game = null;

    // 通过@Autowired注解，将UserMapper的实例注入到WebSocketServer类中
    @Autowired
    public void setUserMapper(UserMapper userMapper) {
        WebSocketServer.userMapper = userMapper;
    }

    // 当有新连接建立时调用
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) throws IOException {
        this.session = session;  // 将当前会话对象赋值给实例变量
        System.out.println("connected to websocket");  // 输出连接成功的日志

        // 从路径参数中获取用户ID并转换为Integer类型
        int userId = JwtAuthentication.getUserId(token);

        // 从数据库中查询用户信息，并赋值给当前实例的user变量
        user = userMapper.selectById(userId);

        // 如果用户信息存在，将用户和当前WebSocketServer实例添加到用户集合中
        if (user != null) {
            users.put(userId, this);
            System.out.println(user + " connected!");  // 输出用户连接成功的日志
        } else {
            session.close();  // 如果用户信息不存在，关闭当前WebSocket会话
        }
    }

    // 当连接关闭时调用
    @OnClose
    public void onClose() {
        System.out.println("disconnected from websocket");  // 输出断开连接的日志

        // 如果当前用户不为空，从用户集合中移除该用户，并将其从匹配池中移除
        if (user != null) {
            users.remove(user.getId());
            matchpool.remove(user);
        }
    }

    // 开始匹配
    private void startMatching() {
        System.out.println("start matching");  // 输出开始匹配的日志
        matchpool.add(user);  // 将当前用户加入匹配池

        // 当匹配池中有超过一个用户时，进行匹配
        while (matchpool.size() > 1) {
            Iterator<User> it = matchpool.iterator();
            User a = it.next(), b = it.next();  // 取出两个用户进行匹配
            matchpool.remove(a);
            matchpool.remove(b);

            System.out.println("-----------------");
            System.out.println("匹配成功！");
            System.out.println("玩家A：" + a.getUsername());
            System.out.println("玩家B：" + b.getUsername());
            System.out.println("-----------------");

            // 创建新的游戏实例
            Game game = new Game(13, 14, 20, a.getId(), b.getId());
            game.createMap();  // 创建游戏地图

            users.get(a.getId()).game = game;
            users.get(b.getId()).game = game;

            game.start();

            JSONObject respGame = new JSONObject();
            respGame.put("a_id", game.getPlayerA().getId());
            respGame.put("a_sx", game.getPlayerA().getSx());
            respGame.put("a_sy", game.getPlayerA().getSy());
            respGame.put("b_id", game.getPlayerB().getId());
            respGame.put("b_sx", game.getPlayerB().getSx());
            respGame.put("b_sy", game.getPlayerB().getSy());
            respGame.put("map", game.getG());

            // 给用户a发送匹配成功的消息，包括对手信息和游戏地图
            JSONObject respA = new JSONObject();
            respA.put("event", "start-matching");
            respA.put("opponent_username", b.getUsername());
            respA.put("opponent_photo", b.getPhoto());
            respA.put("game", respGame);
            users.get(a.getId()).sendMessage(respA.toJSONString());

            // 给用户b发送匹配成功的消息，包括对手信息和游戏地图
            JSONObject respB = new JSONObject();
            respB.put("event", "start-matching");
            respB.put("opponent_username", a.getUsername());
            respB.put("opponent_photo", a.getPhoto());
            respB.put("game", respGame);
            users.get(b.getId()).sendMessage(respB.toJSONString());
        }
    }

    // 停止匹配
    private void stopMatching() {
        System.out.println("stop matching");  // 输出停止匹配的日志
        matchpool.remove(user);  // 将当前用户从匹配池中移除
    }

    private void move(int direction) {
        if (game.getPlayerA().getId().equals(user.getId())) {
            game.setNextStepA(direction);
        } else if (game.getPlayerB().getId().equals(user.getId())) {
            game.setNextStepB(direction);
        }
    }

    // 当从客户端收到消息时调用
    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("received message: " + message);  // 输出接收到的消息
        JSONObject data = JSONObject.parseObject(message);  // 将接收到的消息解析为JSON对象
        String event = data.getString("event");  // 获取事件类型
        if ("start-matching".equals(event)) {
            startMatching();  // 如果事件为“start-matching”，开始匹配
        } else if ("stop-matching".equals(event)) {
            stopMatching();   // 否则，停止匹配
        } else if ("move".equals(event)) {
            move(data.getInteger("direction"));
        }
    }

    // 当发生错误时调用
    @OnError
    public void onError(Session session, Throwable error) {
        error.printStackTrace();  // 打印错误堆栈信息
    }

    // 发送消息给客户端
    public void sendMessage(String message) {
        // 使用同步块，确保在多个线程之间发送消息时的线程安全
        synchronized (session) {
            session.getAsyncRemote().sendText(message);  // 异步发送消息给客户端
        }
    }
}