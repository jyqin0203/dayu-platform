package cn.edu.fudan.dayu.catalog.application;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.Mockito.*;

class CatalogChangedPublicationTest {
    private static final ProductCode CODE = new ProductCode("COT");
    private static final ProductId ID = new ProductId(9);
    private static final ActorContext ADMIN = new ActorContext(new UserId(1), "lab", UserRole.ADMIN);

    @Test
    void successfulWritePublishesAffectedProduct() {
        CatalogRepository repository = mock(CatalogRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(repository.insert(any(), eq(ADMIN.userId()), any())).thenReturn(ID);
        when(repository.lock(ID)).thenReturn(Optional.of(detail()));
        CatalogService service = new CatalogService(repository, mock(ColorbarVerifier.class), events);

        service.createProduct(command(), ADMIN);

        verify(events).publishEvent(new CatalogChanged(CODE));
    }

    @Test
    void failedWriteDoesNotPublishChange() {
        CatalogRepository repository = mock(CatalogRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(repository.insert(any(), eq(ADMIN.userId()), any())).thenThrow(new IllegalStateException("write failed"));
        CatalogService service = new CatalogService(repository, mock(ColorbarVerifier.class), events);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createProduct(command(), ADMIN))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(events);
    }

    private static CreateProductCommand command() {
        return new CreateProductCommand(CODE, "云光学厚度", "Cloud Optical Thickness", "CPP", null,
                "说明", "description", "lab", "algorithm", "source", null, false, null, 2);
    }

    private static ProductDetail detail() {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        return new ProductDetail(new ProductSummary(ID, CODE, "云光学厚度", "Cloud Optical Thickness", "CPP",
                null, "lab", "algorithm", "source", null, ProductStatus.DRAFT, 2),
                "说明", "description", false, null, List.of(), null, now, now);
    }
}
