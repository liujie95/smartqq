package com.scienjus.smartqq.callback;

import com.scienjus.smartqq.model.Result;
import com.scienjus.smartqq.model.UserInfo;

/**
 * Created by liujie on 2017/11/11.
 */
public interface LoginCallback {

    void onFailed(Result<?> result);

    void onSuccess(UserInfo userInfo);

    void onException(Throwable throwable);
}
