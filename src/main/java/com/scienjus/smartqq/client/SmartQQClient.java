package com.scienjus.smartqq.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.scienjus.smartqq.constant.ApiURL;
import com.scienjus.smartqq.exception.SmartqqRuntimeException;
import com.scienjus.smartqq.listener.SmartqqListener;
import com.scienjus.smartqq.model.*;
import org.apache.log4j.Logger;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.HttpCookie;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Api客户端.
 *
 * @author ScienJus
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @author Xianguang Zhou <xianguang.zhou@outlook.com>
 * @date 2015/12/18.
 */
public class SmartQQClient implements Closeable {

    //日志
    private static final Logger LOGGER = Logger.getLogger(SmartQQClient.class);

    //消息id，这个好像可以随便设置，所以设成全局的
    private static long MESSAGE_ID = 43690001;

    //客户端id，固定的
    private static final long Client_ID = 53999199;

    //HTTP客户端
    private HttpClient httpClient;

    //鉴权参数
    private String ptwebqq;

    private String vfwebqq;

    private long uin;

    private String psessionid;

    //线程开关
    private volatile boolean pollStarted;

    private SmartqqListener listener;

    public SmartQQClient(final SmartqqListener listener) throws Exception {
        this.listener = listener;

        this.httpClient = new HttpClient(new SslContextFactory());
        this.httpClient.start();

        login();
        if (listener != null) {
            this.pollStarted = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        if (!pollStarted) {
                            return;
                        }
                        try {
                            pollMessage(listener);
                        } catch (Exception ignore) {
                            LOGGER.error(ignore.getMessage());
                        }
                    }
                }
            }).start();
        }
    }

    /**
     * 登录
     */
    private void login() throws InterruptedException, ExecutionException, TimeoutException {
        getQRCode();
        String url = verifyQRCode();
        getPtwebqq(url);
        getVfwebqq();
        getUinAndPsessionid();
    }

    //登录流程1：获取二维码
    private void getQRCode() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取二维码");

        byte[] imageBytes = httpClient.newRequest(ApiURL.GET_QR_CODE.getUrl())
                .method(HttpMethod.GET)
                .agent(ApiURL.USER_AGENT)
                .send().getContent();
        listener.onQrCodeImage(imageBytes);

        LOGGER.info("二维码已获取");
    }

    //登录流程2：校验二维码
    private String verifyQRCode() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("等待扫描二维码");

        //阻塞直到确认二维码认证成功
        while (true) {
            sleep(1);
            ContentResponse response = get(ApiURL.VERIFY_QR_CODE);
            String result = response.getContentAsString();
            if (result.contains("成功")) {
                for (String content : result.split("','")) {
                    if (content.startsWith("http")) {
                        LOGGER.info("正在登录，请稍后");

                        return content;
                    }
                }
            } else if (result.contains("已失效")) {
                LOGGER.info("二维码已失效，尝试重新获取二维码");
                getQRCode();
            }
        }

    }

    //登录流程3：获取ptwebqq
    private void getPtwebqq(String url) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取ptwebqq");

        ContentResponse response = get(ApiURL.GET_PTWEBQQ, url);
        List<HttpCookie> httpCookieList = httpClient.getCookieStore().get(response.getRequest().getURI());
        for (HttpCookie httpCookie : httpCookieList) {
            if ("ptwebqq".equals(httpCookie.getName())) {
                this.ptwebqq = httpCookie.getValue();
                break;
            }
        }
    }

    //登录流程4：获取vfwebqq
    private void getVfwebqq() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取vfwebqq");

        ContentResponse response = get(ApiURL.GET_VFWEBQQ, ptwebqq);
        this.vfwebqq = getJsonObjectResult(response).getString("vfwebqq");
    }

    //登录流程5：获取uin和psessionid
    private void getUinAndPsessionid() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取uin和psessionid");

        JSONObject r = new JSONObject();
        r.put("ptwebqq", ptwebqq);
        r.put("clientid", Client_ID);
        r.put("psessionid", "");
        r.put("status", "online");

        ContentResponse response = post(ApiURL.GET_UIN_AND_PSESSIONID, r);
        JSONObject result = getJsonObjectResult(response);
        this.psessionid = result.getString("psessionid");
        this.uin = result.getLongValue("uin");
    }

    /**
     * 获取群列表
     *
     * @return
     */
    public List<Group> getGroupList() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取群列表");

        JSONObject r = new JSONObject();
        r.put("vfwebqq", vfwebqq);
        r.put("hash", hash());

        ContentResponse response = post(ApiURL.GET_GROUP_LIST, r);
        JSONObject result = getJsonObjectResult(response);
        return JSON.parseArray(result.getJSONArray("gnamelist").toJSONString(), Group.class);
    }

    /**
     * 拉取消息
     *
     * @param callback 获取消息后的回调
     */
    private void pollMessage(SmartqqListener callback) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始接收消息");

        JSONObject r = new JSONObject();
        r.put("ptwebqq", ptwebqq);
        r.put("clientid", Client_ID);
        r.put("psessionid", psessionid);
        r.put("key", "");

        ContentResponse response = post(ApiURL.POLL_MESSAGE, r);
        JSONArray array = getJsonArrayResult(response);
        for (int i = 0; array != null && i < array.size(); i++) {
            JSONObject message = array.getJSONObject(i);
            String type = message.getString("poll_type");
            if ("message".equals(type)) {
                callback.onMessage(new Message(message.getJSONObject("value")));
            } else if ("group_message".equals(type)) {
                callback.onGroupMessage(new GroupMessage(message.getJSONObject("value")));
            } else if ("discu_message".equals(type)) {
                callback.onDiscussMessage(new DiscussMessage(message.getJSONObject("value")));
            }
        }
    }

    /**
     * 发送群消息
     *
     * @param groupId 群id
     * @param msg     消息内容
     */
    public void sendMessageToGroup(long groupId, String msg) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始发送群消息");

        JSONObject r = new JSONObject();
        r.put("group_uin", groupId);
        r.put("content", JSON.toJSONString(Arrays.asList(msg, Arrays.asList("font", Font.DEFAULT_FONT))));  //注意这里虽然格式是Json，但是实际是String
        r.put("face", 573);
        r.put("clientid", Client_ID);
        r.put("msg_id", MESSAGE_ID++);
        r.put("psessionid", psessionid);

        ContentResponse response = post(ApiURL.SEND_MESSAGE_TO_GROUP, r);
        checkSendMsgResult(response);
    }

    /**
     * 发送讨论组消息
     *
     * @param discussId 讨论组id
     * @param msg       消息内容
     */
    public void sendMessageToDiscuss(long discussId, String msg) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始发送讨论组消息");

        JSONObject r = new JSONObject();
        r.put("did", discussId);
        r.put("content", JSON.toJSONString(Arrays.asList(msg, Arrays.asList("font", Font.DEFAULT_FONT))));  //注意这里虽然格式是Json，但是实际是String
        r.put("face", 573);
        r.put("clientid", Client_ID);
        r.put("msg_id", MESSAGE_ID++);
        r.put("psessionid", psessionid);

        ContentResponse response = post(ApiURL.SEND_MESSAGE_TO_DISCUSS, r);
        checkSendMsgResult(response);
    }

    /**
     * 发送消息
     *
     * @param friendId 好友id
     * @param msg      消息内容
     */
    public void sendMessageToFriend(long friendId, String msg) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始发送消息");

        JSONObject r = new JSONObject();
        r.put("to", friendId);
        r.put("content", JSON.toJSONString(Arrays.asList(msg, Arrays.asList("font", Font.DEFAULT_FONT))));  //注意这里虽然格式是Json，但是实际是String
        r.put("face", 573);
        r.put("clientid", Client_ID);
        r.put("msg_id", MESSAGE_ID++);
        r.put("psessionid", psessionid);

        ContentResponse response = post(ApiURL.SEND_MESSAGE_TO_FRIEND, r);
        checkSendMsgResult(response);
    }

    /**
     * 获得讨论组列表
     *
     * @return
     */
    public List<Discuss> getDiscussList() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取讨论组列表");

        ContentResponse response = get(ApiURL.GET_DISCUSS_LIST, psessionid, vfwebqq);
        return JSON.parseArray(getJsonObjectResult(response).getJSONArray("dnamelist").toJSONString(), Discuss.class);
    }

    /**
     * 获得好友列表（包含分组信息）
     *
     * @return
     */
    public List<Category> getFriendListWithCategory() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取好友列表");

        JSONObject r = new JSONObject();
        r.put("vfwebqq", vfwebqq);
        r.put("hash", hash());

        ContentResponse response = post(ApiURL.GET_FRIEND_LIST, r);
        JSONObject result = getJsonObjectResult(response);
        //获得好友信息
        Map<Long, Friend> friendMap = parseFriendMap(result);
        //获得分组
        JSONArray categories = result.getJSONArray("categories");
        Map<Integer, Category> categoryMap = new HashMap<>();
        categoryMap.put(0, Category.defaultCategory());
        for (int i = 0; categories != null && i < categories.size(); i++) {
            Category category = categories.getObject(i, Category.class);
            categoryMap.put(category.getIndex(), category);
        }
        JSONArray friends = result.getJSONArray("friends");
        for (int i = 0; friends != null && i < friends.size(); i++) {
            JSONObject item = friends.getJSONObject(i);
            Friend friend = friendMap.get(item.getLongValue("uin"));
            categoryMap.get(item.getIntValue("categories")).addFriend(friend);
        }
        return new ArrayList<>(categoryMap.values());
    }

    /**
     * 获取好友列表
     *
     * @return
     */
    public List<Friend> getFriendList() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取好友列表");

        JSONObject r = new JSONObject();
        r.put("vfwebqq", vfwebqq);
        r.put("hash", hash());

        ContentResponse response = post(ApiURL.GET_FRIEND_LIST, r);
        return new ArrayList<>(parseFriendMap(getJsonObjectResult(response)).values());
    }

    //将json解析为好友列表
    private static Map<Long, Friend> parseFriendMap(JSONObject result) {
        Map<Long, Friend> friendMap = new HashMap<>();
        JSONArray info = result.getJSONArray("info");
        for (int i = 0; info != null && i < info.size(); i++) {
            JSONObject item = info.getJSONObject(i);
            Friend friend = new Friend();
            friend.setUserId(item.getLongValue("uin"));
            friend.setNickname(item.getString("nick"));
            friendMap.put(friend.getUserId(), friend);
        }
        JSONArray marknames = result.getJSONArray("marknames");
        for (int i = 0; marknames != null && i < marknames.size(); i++) {
            JSONObject item = marknames.getJSONObject(i);
            friendMap.get(item.getLongValue("uin")).setMarkname(item.getString("markname"));
        }
        JSONArray vipinfo = result.getJSONArray("vipinfo");
        for (int i = 0; vipinfo != null && i < vipinfo.size(); i++) {
            JSONObject item = vipinfo.getJSONObject(i);
            Friend friend = friendMap.get(item.getLongValue("u"));
            friend.setVip(item.getIntValue("is_vip") == 1);
            friend.setVipLevel(item.getIntValue("vip_level"));
        }
        return friendMap;
    }

    /**
     * 获得当前登录用户的详细信息
     *
     * @return
     */
    public UserInfo getAccountInfo() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取登录用户信息");

        ContentResponse response = get(ApiURL.GET_ACCOUNT_INFO);
        return JSON.parseObject(getJsonObjectResult(response).toJSONString(), UserInfo.class);
    }

    /**
     * 获得好友的详细信息
     *
     * @return
     */
    public UserInfo getFriendInfo(long friendId) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取好友信息");

        ContentResponse response = get(ApiURL.GET_FRIEND_INFO, friendId, vfwebqq, psessionid);
        return JSON.parseObject(getJsonObjectResult(response).toJSONString(), UserInfo.class);
    }

    /**
     * 获得最近会话列表
     *
     * @return
     */
    public List<Recent> getRecentList() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取最近会话列表");

        JSONObject r = new JSONObject();
        r.put("vfwebqq", vfwebqq);
        r.put("clientid", Client_ID);
        r.put("psessionid", "");

        ContentResponse response = post(ApiURL.GET_RECENT_LIST, r);
        return JSON.parseArray(getJsonArrayResult(response).toJSONString(), Recent.class);
    }

    /**
     * 获得qq号
     *
     * @param friendId 用户id
     * @return
     */
    public long getQQById(long friendId) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取QQ号");

        ContentResponse response = get(ApiURL.GET_QQ_BY_ID, friendId, vfwebqq);
        return getJsonObjectResult(response).getLongValue("account");
    }

    /**
     * 获得登录状态
     *
     * @return
     */
    public List<FriendStatus> getFriendStatus() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取好友状态");

        ContentResponse response = get(ApiURL.GET_FRIEND_STATUS, vfwebqq, psessionid);
        return JSON.parseArray(getJsonArrayResult(response).toJSONString(), FriendStatus.class);
    }

    /**
     * 获得群的详细信息
     *
     * @param groupCode 群编号
     * @return
     */
    public GroupInfo getGroupInfo(long groupCode) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取群资料");

        ContentResponse response = get(ApiURL.GET_GROUP_INFO, groupCode, vfwebqq);
        JSONObject result = getJsonObjectResult(response);
        GroupInfo groupInfo = result.getObject("ginfo", GroupInfo.class);
        //获得群成员信息
        Map<Long, GroupUser> groupUserMap = new HashMap<>();
        JSONArray minfo = result.getJSONArray("minfo");
        for (int i = 0; minfo != null && i < minfo.size(); i++) {
            GroupUser groupUser = minfo.getObject(i, GroupUser.class);
            groupUserMap.put(groupUser.getUin(), groupUser);
            groupInfo.addUser(groupUser);
        }
        JSONArray stats = result.getJSONArray("stats");
        for (int i = 0; stats != null && i < stats.size(); i++) {
            JSONObject item = stats.getJSONObject(i);
            GroupUser groupUser = groupUserMap.get(item.getLongValue("uin"));
            groupUser.setClientType(item.getIntValue("client_type"));
            groupUser.setStatus(item.getIntValue("stat"));
        }
        JSONArray cards = result.getJSONArray("cards");
        for (int i = 0; cards != null && i < cards.size(); i++) {
            JSONObject item = cards.getJSONObject(i);
            groupUserMap.get(item.getLongValue("muin")).setCard(item.getString("card"));
        }
        JSONArray vipinfo = result.getJSONArray("vipinfo");
        for (int i = 0; vipinfo != null && i < vipinfo.size(); i++) {
            JSONObject item = vipinfo.getJSONObject(i);
            GroupUser groupUser = groupUserMap.get(item.getLongValue("u"));
            groupUser.setVip(item.getIntValue("is_vip") == 1);
            groupUser.setVipLevel(item.getIntValue("vip_level"));
        }
        return groupInfo;
    }

    /**
     * 获得讨论组的详细信息
     *
     * @param discussId 讨论组id
     * @return
     */
    public DiscussInfo getDiscussInfo(long discussId) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.debug("开始获取讨论组资料");

        ContentResponse response = get(ApiURL.GET_DISCUSS_INFO, discussId, vfwebqq, psessionid);
        JSONObject result = getJsonObjectResult(response);
        DiscussInfo discussInfo = result.getObject("info", DiscussInfo.class);
        //获得讨论组成员信息
        Map<Long, DiscussUser> discussUserMap = new HashMap<>();
        JSONArray minfo = result.getJSONArray("mem_info");
        for (int i = 0; minfo != null && i < minfo.size(); i++) {
            DiscussUser discussUser = minfo.getObject(i, DiscussUser.class);
            discussUserMap.put(discussUser.getUin(), discussUser);
            discussInfo.addUser(discussUser);
        }
        JSONArray stats = result.getJSONArray("mem_status");
        for (int i = 0; stats != null && i < stats.size(); i++) {
            JSONObject item = stats.getJSONObject(i);
            DiscussUser discussUser = discussUserMap.get(item.getLongValue("uin"));
            discussUser.setClientType(item.getIntValue("client_type"));
            discussUser.setStatus(item.getString("status"));
        }
        return discussInfo;
    }

    //发送get请求
    private ContentResponse get(ApiURL url, Object... params) throws InterruptedException, ExecutionException, TimeoutException {
        return httpClient.newRequest(url.buildUrl(params))
                .method(HttpMethod.GET)
                .agent(ApiURL.USER_AGENT)
                .header("Referer", url.getReferer())
                .send();
    }

    //发送post请求
    private ContentResponse post(ApiURL url, JSONObject r) throws InterruptedException, ExecutionException, TimeoutException {
        return httpClient.newRequest(url.getUrl())
                .method(HttpMethod.POST)
                .agent(ApiURL.USER_AGENT)
                .header("Referer", url.getReferer())
                .header("Origin", url.getOrigin())
                .param("r", r.toJSONString())
                .send();
    }

    //获取返回json的result字段（JSONObject类型）
    private static JSONObject getJsonObjectResult(ContentResponse response) {
        return getResponseJson(response).getJSONObject("result");
    }

    //获取返回json的result字段（JSONArray类型）
    private static JSONArray getJsonArrayResult(ContentResponse response) {
        return getResponseJson(response).getJSONArray("result");
    }

    //检查消息是否发送成功
    private static void checkSendMsgResult(ContentResponse response) {
        if (response.getStatus() != 200) {
            LOGGER.error(String.format("发送失败，Http返回码[%d]", response.getStatus()));
        }
        JSONObject json = JSON.parseObject(response.getContentAsString());
        Integer errCode = json.getInteger("errCode");
        if (errCode != null && errCode == 0) {
            LOGGER.debug("发送成功!");
        } else {
            LOGGER.error(String.format("发送失败，Api返回码[%d]", json.getInteger("retcode")));
        }
    }

    //检验Json返回结果
    private static JSONObject getResponseJson(ContentResponse response) {
        if (response.getStatus() != 200) {
            throw new SmartqqRuntimeException(String.format("请求失败，Http返回码[%d]", response.getStatus()));
        }
        JSONObject json = JSON.parseObject(response.getContentAsString());
        Integer retCode = json.getInteger("retcode");
        if (retCode == null || retCode != 0) {
            if (retCode != null && retCode == 103) {
                LOGGER.error("请求失败，Api返回码[103]。你需要进入http://w.qq.com，检查是否能正常接收消息。如果可以的话点击[设置]->[退出登录]后查看是否恢复正常");
            } else {
                throw new SmartqqRuntimeException(String.format("请求失败，Api返回码[%d]", retCode));
            }
        }
        return json;
    }

    //hash加密方法
    private String hash() {
        return hash(uin, ptwebqq);
    }

    //线程暂停
    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException ignored) {
        }
    }

    //hash加密方法
    private static String hash(long x, String K) {
        int[] N = new int[4];
        for (int T = 0; T < K.length(); T++) {
            N[T % 4] ^= K.charAt(T);
        }
        String[] U = {"EC", "OK"};
        long[] V = new long[4];
        V[0] = x >> 24 & 255 ^ U[0].charAt(0);
        V[1] = x >> 16 & 255 ^ U[0].charAt(1);
        V[2] = x >> 8 & 255 ^ U[1].charAt(0);
        V[3] = x & 255 ^ U[1].charAt(1);

        long[] U1 = new long[8];

        for (int T = 0; T < 8; T++) {
            U1[T] = T % 2 == 0 ? N[T >> 1] : V[T >> 1];
        }

        String[] N1 = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F"};
        String V1 = "";
        for (long aU1 : U1) {
            V1 += N1[(int) ((aU1 >> 4) & 15)];
            V1 += N1[(int) (aU1 & 15)];
        }
        return V1;
    }

    @Override
    public void close() throws IOException {
        this.pollStarted = false;
        if (this.httpClient != null) {
            try {
                this.httpClient.stop();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
