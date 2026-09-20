package com.SocialService.Communities.Clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@FeignClient(
        name = "${blob.service.name}",
        url = "${blob.service.url}",
        path = "/api/vault",
        configuration = FeignInterceptorConfig.class)
public interface BlobClient {

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadMedia(
            @RequestPart("file") MultipartFile file,
            @RequestParam("userId") String userId
    );

    @DeleteMapping("/delete/{mediaId}")
    Map<String, Object> deleteMedia(@PathVariable("mediaId") String mediaId);
}