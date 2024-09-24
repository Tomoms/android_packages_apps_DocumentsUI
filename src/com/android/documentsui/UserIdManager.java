/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.documentsui.base.SharedMinimal.DEBUG;
import static com.android.documentsui.util.FlagUtils.isSupportVisibleBackgroundUserFlagEnabled;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.documentsui.base.Features;
import com.android.documentsui.base.UserId;
import com.android.documentsui.util.VersionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Interface to query user ids.
 */
public interface UserIdManager {

    /**
     * Returns the {@UserId} of each profile which should be queried for documents. This will always
     * include {@link UserId#CURRENT_USER}.
     */
    List<UserId> getUserIds();

    /**
     * Returns the system user from {@link #getUserIds()} if the list at least 2 users. Otherwise,
     * returns null.
     */
    @Nullable
    UserId getSystemUser();

    /**
     * Returns the managed user from {@link #getUserIds()} if the list at least 2 users. Otherwise,
     * returns null.
     */
    @Nullable
    UserId getManagedUser();

    /**
     * Creates an implementation of {@link UserIdManager}.
     */
    static UserIdManager create(Context context) {
        return new RuntimeUserIdManager(context);
    }

    /**
     * Implementation of {@link UserIdManager}.
     */
    final class RuntimeUserIdManager implements UserIdManager {

        private static final String TAG = "UserIdManager";

        private final Context mContext;
        private final UserId mCurrentUser;
        private final boolean mIsDeviceSupported;

        @GuardedBy("mUserIds")
        private final List<UserId> mUserIds = new ArrayList<>();
        @GuardedBy("mUserIds")
        private UserId mSystemUser = null;
        @GuardedBy("mUserIds")
        private UserId mManagedUser = null;

        private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (mUserIds) {
                    mUserIds.clear();
                }
            }
        };

        private RuntimeUserIdManager(Context context) {
            this(context, UserId.CURRENT_USER,
                    Features.CROSS_PROFILE_TABS && isDeviceSupported(context));
        }

        @VisibleForTesting
        RuntimeUserIdManager(Context context, UserId currentUser, boolean isDeviceSupported) {
            mContext = context.getApplicationContext();
            mCurrentUser = checkNotNull(currentUser);
            mIsDeviceSupported = isDeviceSupported;

            if (isSupportVisibleBackgroundUserFlagEnabled()
                    && mCurrentUser.isVisibleBackgroundFullUser(mContext)) {
                // The visible background user does not have profile users.
                // Visible background users should not be affected by events related to profile
                // users.
                return;
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
            mContext.registerReceiver(mIntentReceiver, filter);
        }

        @Override
        public List<UserId> getUserIds() {
            synchronized (mUserIds) {
                if (mUserIds.isEmpty()) {
                    mUserIds.addAll(getUserIdsInternal());
                }
            }
            return mUserIds;
        }

        @Override
        public UserId getSystemUser() {
            synchronized (mUserIds) {
                if (mUserIds.isEmpty()) {
                    getUserIds();
                }
            }
            return mSystemUser;
        }

        @Override
        public UserId getManagedUser() {
            synchronized (mUserIds) {
                if (mUserIds.isEmpty()) {
                    getUserIds();
                }
            }
            return mManagedUser;
        }

        private List<UserId> getUserIdsInternal() {
            final List<UserId> result = new ArrayList<>();
            result.add(mCurrentUser);

            // If the feature is disabled, return a list just containing the current user.
            if (!mIsDeviceSupported) {
                return result;
            }

            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            if (userManager == null) {
                Log.e(TAG, "cannot obtain user manager");
                return result;
            }

            final List<UserHandle> userProfiles = userManager.getUserProfiles();
            if (userProfiles.size() < 2) {
                return result;
            }

            final UserHandle currentUser = UserHandle.of(mCurrentUser.getIdentifier());
            final UserHandle profileParent = userManager.getProfileParent(currentUser);
            if (mCurrentUser.isManagedProfile(userManager)) {
                // 1. If the current user is a managed user, add the parent user.
                if (profileParent == null) {
                    Log.e(TAG, "managed user has no parent!");
                } else {
                    result.add(0, UserId.of(profileParent));
                }
            } else if (profileParent == null) {
                // 2. We are the parent. getProfileParent() is designed to return null for the
                // parent of the group, or for something that is not a profile. If we were not
                // either a profile or the parent, we would not be included in userProfiles,
                // unless userProfiles only had a single element, in which case we would have
                // returned earlier. We must be the parent, then, so we add any managed profiles.
                result.addAll(userProfiles.stream()
                        .map(UserId::of)
                        .filter(userId ->
                                !mCurrentUser.equals(userId) && userId.isManagedProfile(userManager)
                        ).collect(Collectors.toList()));
            } else {
                // 3. If we cannot resolve the users properly, we will disable the cross-profile
                // feature by returning just the current user.
                if (DEBUG) {
                    Log.w(TAG, "The current user " + mCurrentUser.getIdentifier()
                            + " is neither full nor managed user.");
                }
            }

            return result;
        }

        private static boolean isDeviceSupported(Context context) {
            // The feature requires Android R DocumentsContract APIs and INTERACT_ACROSS_USERS
            // permission.
            return VersionUtils.isAtLeastR()
                    && context.checkSelfPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }
}
