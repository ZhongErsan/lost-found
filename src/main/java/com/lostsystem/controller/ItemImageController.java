package com.lostsystem.controller;

import com.lostsystem.auth.UserContext;
import com.lostsystem.service.ItemImageService;
import com.lostsystem.vo.ItemImageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemImageController {

    private final ItemImageService itemImageService;

    @PostMapping(
            value = "/{type}/{id}/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ItemImageVO> upload(
            @PathVariable("type") String type,
            @PathVariable("id") Long id,
            @RequestParam("file") MultipartFile file
    ) {
        Long userId = UserContext.require().userId();

        String imageUrl = itemImageService.upload(
                userId,
                type,
                id,
                file
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ItemImageVO(imageUrl));
    }
}