package com.modhub.nexus

/** Trimmed-down copies of real Nexus v1 responses (field names as documented). */
object NexusFixtures {
    val MOD_JSON = """
        {
          "name": "Content Patcher",
          "summary": "Loads content packs.<br />No XNB needed.",
          "description": "[b]long[/b]",
          "picture_url": "https://staticdelivery.nexusmods.com/cp.png",
          "mod_downloads": 5000000,
          "mod_unique_downloads": 3000000,
          "uid": 5596342000000,
          "mod_id": 1915,
          "game_id": 1303,
          "allow_rating": true,
          "domain_name": "stardewvalley",
          "category_id": 18,
          "version": "2.4.0",
          "endorsement_count": 90000,
          "created_timestamp": 1500000000,
          "created_time": "2017-07-14T02:40:00.000+00:00",
          "updated_timestamp": 1700000100,
          "updated_time": "2023-11-14T22:15:00.000+00:00",
          "author": "Pathoschild",
          "uploaded_by": "Pathoschild",
          "uploaded_users_profile_url": "https://www.nexusmods.com/users/1552",
          "contains_adult_content": false,
          "status": "published",
          "available": true,
          "user": {"member_id": 1552, "member_group_id": 27, "name": "Pathoschild"},
          "endorsement": null
        }
    """.trimIndent()

    fun contentPatcher(): NexusModDto = NexusJson.mod(MOD_JSON)

    val MOD_LIST_JSON = """
        [
          $MOD_JSON,
          {"mod_id": 99999, "game_id": 1303, "domain_name": "stardewvalley", "status": "hidden", "available": false}
        ]
    """.trimIndent()

    val FILES_JSON = """
        {
          "files": [
            {"id":[100,1303],"uid":1,"file_id":100,"name":"Content Patcher","version":"2.3.0","category_id":4,
             "category_name":"OLD_VERSION","is_primary":false,"size":1000,"file_name":"cp-2.3.0.zip",
             "uploaded_timestamp":1690000000,"mod_version":"2.3.0","size_in_bytes":1000000,"description":"old"},
            {"id":[101,1303],"uid":2,"file_id":101,"name":"Content Patcher","version":"2.4.0","category_id":1,
             "category_name":"MAIN","is_primary":true,"size":1024,"size_kb":1024,
             "file_name":"Content Patcher 2.4.0-1915-2-4-0-1700000000.zip","uploaded_timestamp":1700000000,
             "mod_version":"2.4.0","external_virus_scan_url":"https://virustotal","description":"Main<br/>file",
             "size_in_bytes":1048576,"content_preview_link":"https://x"},
            {"id":[103,1303],"file_id":103,"name":"Extras","version":"1.0","category_id":3,"category_name":"OPTIONAL",
             "is_primary":false,"size_kb":2,"file_name":"extras.zip","uploaded_timestamp":1695000000,"size_in_bytes":null}
          ],
          "file_updates": [{"old_file_id":100,"new_file_id":101,"old_file_name":"a","new_file_name":"b","uploaded_timestamp":1700000000,"uploaded_time":"x"}]
        }
    """.trimIndent()

    val DOWNLOAD_LINKS_JSON = """
        [
          {"name":"Nexus CDN","short_name":"Nexus CDN","URI":"https://cf-files.nexusmods.com/cdn/1/a.zip?md5=x"},
          {"name":"Amsterdam","short_name":"Amsterdam","URI":"https://amsterdam.nexus-cdn.com/1/a.zip"}
        ]
    """.trimIndent()
}
