package com.yunhwan.auth.error.usecase.autherror.cluster;

public interface AuthErrorClusterLinker {
    void link(Long authErrorId, String stackHash);
}
