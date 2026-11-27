import test from 'node:test';
import assert from 'node:assert/strict';
import { loadPhotoFeed } from './social.js';

function queryResult(data, error = null) {
  const query = {
    select() { return query; },
    order() { return query; },
    limit() { return query; },
    in() { return query; },
    eq() { return query; },
    then(resolve, reject) { return Promise.resolve({ data, error }).then(resolve, reject); }
  };
  return query;
}

function mockSupabase({ photos, profiles, likes, unavailablePaths = [] }) {
  return {
    from(table) {
      if (table === 'profile_photos') return queryResult(photos);
      if (table === 'profiles') return queryResult(profiles);
      if (table === 'profile_photo_likes') return queryResult(likes);
      throw new Error('Unexpected table: ' + table);
    },
    storage: {
      from(bucket) {
        assert.equal(bucket, 'nexura-social-media');
        return {
          async createSignedUrl(path) {
            if (unavailablePaths.includes(path)) return { data: null, error: new Error('missing') };
            return { data: { signedUrl: 'https://media.example/' + encodeURIComponent(path) }, error: null };
          }
        };
      }
    }
  };
}

test('community photo feed resolves authors and aggregates likes', async () => {
  const supabase = mockSupabase({
    photos: [
      { id: 'photo-1', user_id: 'user-1', storage_path: 'user-1/photos/a.jpg', caption: 'Hello', created_at: '2026-11-26T10:00:00Z' },
      { id: 'photo-2', user_id: 'user-2', storage_path: 'user-2/photos/b.jpg', caption: '', created_at: '2026-11-26T09:00:00Z' }
    ],
    profiles: [
      { id: 'user-1', display_name: 'Alice', username: 'alice', avatar_url: '🙂' },
      { id: 'user-2', display_name: 'Bob', username: 'bob', avatar_url: '🙂' }
    ],
    likes: [
      { photo_id: 'photo-1', user_id: 'current-user' },
      { photo_id: 'photo-1', user_id: 'user-2' },
      { photo_id: 'photo-2', user_id: 'user-1' }
    ]
  });

  const feed = await loadPhotoFeed(supabase, 'current-user');

  assert.equal(feed.length, 2);
  assert.equal(feed[0].profile.display_name, 'Alice');
  assert.equal(feed[0].likeCount, 2);
  assert.equal(feed[0].likedByMe, true);
  assert.equal(feed[1].profile.username, 'bob');
  assert.equal(feed[1].likeCount, 1);
  assert.equal(feed[1].likedByMe, false);
  assert.match(feed[0].url, /^https:\/\/media\.example\//);
});

test('community photo feed skips unavailable private storage objects', async () => {
  const supabase = mockSupabase({
    photos: [
      { id: 'photo-good', user_id: 'user-1', storage_path: 'user-1/photos/good.jpg', caption: '', created_at: '2026-11-26T10:00:00Z' },
      { id: 'photo-missing', user_id: 'user-2', storage_path: 'user-2/photos/missing.jpg', caption: '', created_at: '2026-11-26T09:00:00Z' }
    ],
    profiles: [{ id: 'user-1', display_name: 'Alice' }, { id: 'user-2', display_name: 'Bob' }],
    likes: [],
    unavailablePaths: ['user-2/photos/missing.jpg']
  });

  const feed = await loadPhotoFeed(supabase, 'current-user');

  assert.deepEqual(feed.map(item => item.id), ['photo-good']);
});
