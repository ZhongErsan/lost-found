package com.lostsystem.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.entity.LostRegisterEntity;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.mapper.LostRegisterMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Service
public class ItemImageService {

    // 最大上传大小：5MB
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    // 限制图片尺寸，避免过大的图片占用过多内存
    private static final int MAX_SIDE = 8000;
    private static final long MAX_PIXELS = 16_000_000L;

    private final LostPropertyMapper lostPropertyMapper;
    private final LostRegisterMapper lostRegisterMapper;
    private final Path uploadDirectory;

    public ItemImageService(
            LostPropertyMapper lostPropertyMapper,
            LostRegisterMapper lostRegisterMapper,
            @Value("${lost-found.upload.path:./data/uploads}")
            String uploadPath
    ) {
        this.lostPropertyMapper = lostPropertyMapper;
        this.lostRegisterMapper = lostRegisterMapper;
        this.uploadDirectory = Path.of(uploadPath)
                .toAbsolutePath()
                .normalize();
    }

    public String upload(
            Long userId,
            String type,
            Long id,
            MultipartFile file
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录");
        }

        if (id == null || id <= 0) {
            throw new BusinessException(400, "登记编号必须大于0");
        }

        if (!"lost".equals(type) && !"found".equals(type)) {
            throw new BusinessException(
                    400,
                    "登记类型只能是 lost 或 found"
            );
        }

        // 上传文件前，先检查登记归属和当前状态
        Long version = requireOwnedVersion(userId, type, id);

        DecodedImage decoded = decodeImage(file);

        // 文件名由后端生成，不使用用户上传的文件名
        String extension = "jpeg".equals(decoded.format())
                ? ".jpg"
                : ".png";

        String filename = UUID.randomUUID() + extension;
        Path target = uploadDirectory.resolve(filename);

        try {
            Files.createDirectories(uploadDirectory);

            // 重新编码后保存，保证保存的文件是实际的图片
            boolean written = ImageIO.write(
                    decoded.image(),
                    decoded.format(),
                    target.toFile()
            );

            if (!written) {
                throw new IOException("未找到对应的图片编码器");
            }
        } catch (IOException exception) {
            deleteNewFile(target);

            throw new BusinessException(
                    500,
                    "图片保存失败，请稍后重试",
                    exception
            );
        } finally {
            decoded.image().flush();
        }

        String imageUrl = "/uploads/" + filename;

        try {
            int rows;

            if ("found".equals(type)) {
                rows = lostPropertyMapper.updateImage(
                        id,
                        userId,
                        version,
                        imageUrl
                );
            } else {
                rows = lostRegisterMapper.updateImage(
                        id,
                        userId,
                        version,
                        imageUrl
                );
            }

            if (rows != 1) {
                throw new BusinessException(
                        409,
                        "登记信息已经发生变化，请重新读取后再上传"
                );
            }
        } catch (RuntimeException exception) {
            // 数据库没有成功更新时，清理本次上传的新文件
            deleteNewFile(target);
            throw exception;
        }

        return imageUrl;
    }

    private Long requireOwnedVersion(
            Long userId,
            String type,
            Long id
    ) {
        if ("found".equals(type)) {
            LostPropertyEntity item =
                    lostPropertyMapper.selectOne(
                            new QueryWrapper<LostPropertyEntity>()
                                    .eq("id", id)
                                    .eq("user_id", userId)
                    );

            if (item == null) {
                throw new BusinessException(
                        404,
                        "拾物登记不存在或无权修改"
                );
            }

            if (!"AVAILABLE".equals(item.getStatus())) {
                throw new BusinessException(
                        409,
                        "当前拾物登记已进入交接或归还流程，不能修改图片"
                );
            }

            return item.getVersion();
        }

        LostRegisterEntity item =
                lostRegisterMapper.selectOne(
                        new QueryWrapper<LostRegisterEntity>()
                                .eq("id", id)
                                .eq("user_id", userId)
                );

        if (item == null) {
            throw new BusinessException(
                    404,
                    "丢失登记不存在或无权修改"
            );
        }

        if (!"SEARCHING".equals(item.getStatus())) {
            throw new BusinessException(
                    409,
                    "当前丢失登记已进入交接或完成流程，不能修改图片"
            );
        }

        return item.getVersion();
    }

    private DecodedImage decodeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的图片");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "图片不能超过5MB");
        }

        try (
                MemoryCacheImageInputStream input =
                        new MemoryCacheImageInputStream(
                                new ByteArrayInputStream(file.getBytes())
                        )
        ) {
            Iterator<ImageReader> readers =
                    ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw new BusinessException(
                        400,
                        "文件不是有效的图片"
                );
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(input, true, true);

                String format = reader.getFormatName()
                        .toLowerCase(Locale.ROOT);

                if (!"jpeg".equals(format) && !"png".equals(format)) {
                    throw new BusinessException(
                            400,
                            "只支持JPG和PNG图片"
                    );
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width <= 0
                        || height <= 0
                        || width > MAX_SIDE
                        || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) {
                    throw new BusinessException(
                            400,
                            "图片尺寸过大，请缩小后上传"
                    );
                }

                BufferedImage image = reader.read(0);

                if (image == null) {
                    throw new BusinessException(
                            400,
                            "图片读取失败，请更换图片"
                    );
                }

                return new DecodedImage(image, format);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new BusinessException(
                    400,
                    "图片无法读取，请上传完整的JPG或PNG图片",
                    exception
            );
        }
    }

    private void deleteNewFile(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // 清理失败不覆盖原来的上传错误
        }
    }

    private record DecodedImage(
            BufferedImage image,
            String format
    ) {
    }
}